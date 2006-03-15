/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state;

import EDU.oswego.cs.dl.util.concurrent.ReadWriteLock;
import EDU.oswego.cs.dl.util.concurrent.ReentrantWriterPreferenceReadWriteLock;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.core.nodetype.NodeDefId;
import org.apache.jackrabbit.core.nodetype.NodeTypeConflictException;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.observation.EventStateCollection;
import org.apache.jackrabbit.core.observation.EventStateCollectionFactory;
import org.apache.jackrabbit.core.util.Dumpable;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.uuid.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.List;

/**
 * Shared <code>ItemStateManager</code> (SISM). Caches objects returned from a
 * <code>PersistenceManager</code>. Objects returned by this item state
 * manager are shared among all sessions.
 * <p/>
 * A shared item state manager operates on a <code>PersistenceManager</code>
 * (PM) that is used to load and store the item states. Additionally, a SISM can
 * have <code>VirtualItemStateProvider</code>s (VISP) that are used to provide
 * additional, non-persistent, read-only states. Examples of VISP are the
 * content representation of the NodeTypes (/jcr:system/jcr:nodeTypes) and the
 * version store (/jcr:system/jcr:versionStore). those 2 VISP are added to the
 * SISM during initialization of a workspace. i.e. they are 'mounted' to all
 * workspaces. we assume, that VISP cannot be added dynamically, neither during
 * runtime nor by configuration.
 * <p/>
 * The states from the VISP are readonly. by the exception for node references.
 * remember that the referrers are stored in a {@link NodeReferences} state,
 * having the ID of the target state.
 * <br/>
 * there are 5 types of referential relations to be distinguished:
 * <ol>
 * <li> normal --> normal (references from 'normal' states to 'normal' states)
 *      this is the normal case and will be handled by the SISM.
 *
 * <li> normal --> virtual (references from 'normal' states to 'virtual' states)
 *      those references should be handled by the VISP rather by the SISM.
 *
 * <li> virtual --> normal (references from 'virtual' states to 'normal' states)
 *      such references are not supported. eg. references of versioned nodes do
 *      not impose any constraints on the referenced nodes.
 *
 * <li> virtual --> virtual (references from 'virtual' states to 'virtual'
 *      states of the same VISP).
 *      intra-virtual references are handled by the item state manager of the VISP.
 *
 * <li> virtual --> virtual' (references from 'virtual' states to 'virtual'
 *      states of different VISP).
 *      those do currently not occurr and are therfor not supported.
 * </ol>
 * <p/>
 * if VISP are not dynamic, there is not risk that NV-type references can dangle
 * (since a VISP cannot be 'unmounted', leaving eventual references dangling).
 * although multi-workspace-referrers are not explicitelt supported, the
 * architecture of <code>NodeReferences</code> support multiple referrers with
 * the same PropertyId. So the number of references can be tracked (an example
 * of multi-workspace-refferres is a version referenced by the jcr:baseVersion
 * of several (corresponding) nodes in multiple workspaces).
 * <br/>
 * As mentioned, VN-type references should not impose any constraints on the
 * referrers (e.g. a normal node referenced by a versioned reference property).
 * In case of the version store, the VN-type references are not stored at
 * all, but reinforced as NN-type references in the normal states in case of a
 * checkout operation.
 * <br/>
 * VV-type references should be handled by the respective VISP. they look as
 * NN-type references in the scope if the VISP anyway...so no special treatment
 * should be neccessairy.
 * <br/>
 * VV'-type references are currently not possible, since the version store and
 * virtual nodetype representation don't allow such references.
 */
public class SharedItemStateManager
        implements ItemStateManager, ItemStateListener, Dumpable {

    /**
     * Logger instance
     */
    private static Logger log = LoggerFactory.getLogger(SharedItemStateManager.class);

    /**
     * cache of weak references to ItemState objects issued by this
     * ItemStateManager
     */
    private final ItemStateReferenceCache cache;

    /**
     * Persistence Manager used for loading and storing items
     */
    private final PersistenceManager persistMgr;

    /**
     * node type registry used for identifying referenceable nodes
     */
    private final NodeTypeRegistry ntReg;

    /**
     * Flag indicating whether this item state manager uses node references to
     * verify integrity of its reference properties.
     */
    private final boolean usesReferences;

    /**
     * id of root node
     */
    private final NodeId rootNodeId;

    /**
     * Virtual item state providers
     */
    private VirtualItemStateProvider[] virtualProviders =
            new VirtualItemStateProvider[0];

    /**
     * Read-/Write-Lock to synchronize access on this item state manager.
     */
    private final ReadWriteLock rwLock =
            new ReentrantWriterPreferenceReadWriteLock() {
                /**
                 * Allow reader when there is no active writer, or current
                 * thread owns the write lock (reentrant).
                 */
                protected boolean allowReader() {
                    return activeWriter_ == null ||
                      activeWriter_ == Thread.currentThread();
                }
            };

    /**
     * Creates a new <code>SharedItemStateManager</code> instance.
     *
     * @param persistMgr
     * @param rootNodeId
     * @param ntReg
     */
    public SharedItemStateManager(PersistenceManager persistMgr,
                                  NodeId rootNodeId,
                                  NodeTypeRegistry ntReg,
                                  boolean usesReferences)
            throws ItemStateException {
        cache = new ItemStateReferenceCache();
        this.persistMgr = persistMgr;
        this.ntReg = ntReg;
        this.usesReferences = usesReferences;
        this.rootNodeId = rootNodeId;
        // create root node state if it doesn't yet exist
        if (!hasNonVirtualItemState(rootNodeId)) {
            createRootNodeState(rootNodeId, ntReg);
        }
    }

    //-----------------------------------------------------< ItemStateManager >
    /**
     * {@inheritDoc}
     */
    public ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        acquireReadLock();

        try {
            // check the virtual root ids (needed for overlay)
            for (int i = 0; i < virtualProviders.length; i++) {
                if (virtualProviders[i].isVirtualRoot(id)) {
                    return virtualProviders[i].getItemState(id);
                }
            }
            // check internal first
            if (hasNonVirtualItemState(id)) {
                return getNonVirtualItemState(id);
            }
            // check if there is a virtual state for the specified item
            for (int i = 0; i < virtualProviders.length; i++) {
                if (virtualProviders[i].hasItemState(id)) {
                    return virtualProviders[i].getItemState(id);
                }
            }
        } finally {
            rwLock.readLock().release();
        }
        throw new NoSuchItemStateException(id.toString());
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasItemState(ItemId id) {

        try {
            acquireReadLock();
        } catch (ItemStateException e) {
            return false;
        }

        try {
            if (cache.isCached(id)) {
                return true;
            }

            // check the virtual root ids (needed for overlay)
            for (int i = 0; i < virtualProviders.length; i++) {
                if (virtualProviders[i].isVirtualRoot(id)) {
                    return true;
                }
            }
            // check if this manager has the item state
            if (hasNonVirtualItemState(id)) {
                return true;
            }
            // otherwise check virtual ones
            for (int i = 0; i < virtualProviders.length; i++) {
                if (virtualProviders[i].hasItemState(id)) {
                    return true;
                }
            }
        } finally {
            rwLock.readLock().release();
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public NodeReferences getNodeReferences(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {

        acquireReadLock();

        try {
            // check persistence manager
            try {
                return persistMgr.load(id);
            } catch (NoSuchItemStateException e) {
                // ignore
            }
            // check virtual providers
            for (int i = 0; i < virtualProviders.length; i++) {
                try {
                    return virtualProviders[i].getNodeReferences(id);
                } catch (NoSuchItemStateException e) {
                    // ignore
                }
            }
        } finally {
            rwLock.readLock().release();
        }

        // throw
        throw new NoSuchItemStateException(id.toString());
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasNodeReferences(NodeReferencesId id) {

        try {
            acquireReadLock();
        } catch (ItemStateException e) {
            return false;
        }

        try {
            // check persistence manager
            try {
                if (persistMgr.exists(id)) {
                    return true;
                }
            } catch (ItemStateException e) {
                // ignore
            }
            // check virtual providers
            for (int i = 0; i < virtualProviders.length; i++) {
                if (virtualProviders[i].hasNodeReferences(id)) {
                    return true;
                }
            }
        } finally {
            rwLock.readLock().release();
        }
        return false;
    }

    //----------------------------------------------------< ItemStateListener >
    /**
     * {@inheritDoc}
     */
    public void stateCreated(ItemState created) {
        cache.cache(created);
    }

    /**
     * {@inheritDoc}
     */
    public void stateModified(ItemState modified) {
        // not interested
    }

    /**
     * {@inheritDoc}
     */
    public void stateDestroyed(ItemState destroyed) {
        destroyed.removeListener(this);
        cache.evict(destroyed.getId());
    }

    /**
     * {@inheritDoc}
     */
    public void stateDiscarded(ItemState discarded) {
        discarded.removeListener(this);
        cache.evict(discarded.getId());
    }

    //-------------------------------------------------------------< Dumpable >
    /**
     * {@inheritDoc}
     */
    public void dump(PrintStream ps) {
        ps.println("SharedItemStateManager (" + this + ")");
        ps.println();
        ps.print("[referenceCache] ");
        cache.dump(ps);
    }

    //-------------------------------------------------< misc. public methods >
    /**
     * Disposes this <code>SharedItemStateManager</code> and frees resources.
     */
    public void dispose() {
        // clear cache
        cache.evictAll();
    }

    /**
     * Adds a new virtual item state provider.<p/>
     * NOTE: This method is not synchronized, because it is called right after
     * creation only by the same thread and therefore concurrency issues
     * do not occur. Should this ever change, the synchronization status
     * has to be re-examined.
     *
     * @param prov
     */
    public void addVirtualItemStateProvider(VirtualItemStateProvider prov) {
        VirtualItemStateProvider[] provs =
                new VirtualItemStateProvider[virtualProviders.length + 1];
        System.arraycopy(virtualProviders, 0, provs, 0, virtualProviders.length);
        provs[virtualProviders.length] = prov;
        virtualProviders = provs;
    }

    /**
     * Object representing a single update operation.
     */
    class Update {

        /**
         * Local change log.
         */
        private final ChangeLog local;

        /**
         * Event state collection factory.
         */
        private final EventStateCollectionFactory factory;

        /**
         * Virtual provider containing references to be left out when updating
         * references.
         */
        private final VirtualItemStateProvider virtualProvider;

        /**
         * Shared change log.
         */
        private ChangeLog shared;

        /**
         * Virtual node references.
         */
        private List[] virtualNodeReferences;

        /**
         * Events to dispatch.
         */
        private EventStateCollection events;

        /**
         * Flag indicating whether we are holding write lock.
         */
        private boolean holdingWriteLock;

        /**
         * Create a new instance of this class.
         */
        public Update(ChangeLog local, EventStateCollectionFactory factory,
                      VirtualItemStateProvider virtualProvider) {
            this.local = local;
            this.factory = factory;
            this.virtualProvider = virtualProvider;
        }

        /**
         * Begin update operation. Prepares everything upto the point where
         * the persistence manager's <code>store</code> method may be invoked.
         * If this method succeeds, a write lock will have been acquired on the
         * item state manager and either {@link #end()} or {@link #cancel()} has
         * to be called in order to release it.
         */
        public void begin() throws ItemStateException, ReferentialIntegrityException {
            shared = new ChangeLog();

            virtualNodeReferences = new List[virtualProviders.length];

            try {
                events = factory.createEventStateCollection();
            } catch (RepositoryException e) {
                String msg = "Unable to create event state collection.";
                log.error(msg);
                throw new ItemStateException(msg, e);
            }

            acquireWriteLock();
            holdingWriteLock = true;

            boolean succeeded = false;

            try {
                if (usesReferences) {
                    /**
                     * Update node references based on modifications in change log
                     * (added/modified/removed REFERENCE properties)
                     */
                    updateReferences(local, virtualProvider);
                }

                /**
                 * Check whether reference targets exist/were not removed
                 */
                checkReferentialIntegrity(local);

                /**
                 * Reconnect all items contained in the change log to their
                 * respective shared item and add the shared items to a
                 * new change log.
                 */
                for (Iterator iter = local.modifiedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    state.connect(getItemState(state.getId()));
                    if (state.isStale()) {
                        String msg = state.getId() + " has been modified externally";
                        log.debug(msg);
                        throw new StaleItemStateException(msg);
                    }

                    // update modification count (will be persisted as well)
                    state.getOverlayedState().touch();

                    shared.modified(state.getOverlayedState());
                }
                for (Iterator iter = local.deletedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    state.connect(getItemState(state.getId()));
                    if (state.isStale()) {
                        String msg = state.getId() + " has been modified externally";
                        log.debug(msg);
                        throw new StaleItemStateException(msg);
                    }
                    shared.deleted(state.getOverlayedState());
                }
                for (Iterator iter = local.addedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    state.connect(createInstance(state));
                    shared.added(state.getOverlayedState());
                }

                // filter out virtual node references for later processing
                // (see comment above)
                for (Iterator iter = local.modifiedRefs(); iter.hasNext();) {
                    NodeReferences refs = (NodeReferences) iter.next();
                    boolean virtual = false;
                    NodeId id = refs.getId().getTargetId();
                    for (int i = 0; i < virtualProviders.length; i++) {
                        if (virtualProviders[i].hasItemState(id)) {
                            List virtualRefs = virtualNodeReferences[i];
                            if (virtualRefs == null) {
                                virtualRefs = new LinkedList();
                                virtualNodeReferences[i] = virtualRefs;
                            }
                            virtualRefs.add(refs);
                            virtual = true;
                            break;
                        }
                    }
                    if (!virtual) {
                        // if target of node reference does not lie in a virtual
                        // space, add to modified set of normal provider.
                        shared.modified(refs);
                    }
                }

                /* create event states */
                events.createEventStates(rootNodeId, local,
                        SharedItemStateManager.this);

                /* Push all changes from the local items to the shared items */
                local.push();

                succeeded = true;

            } finally {
                if (!succeeded) {
                    cancel();
                }
            }
        }

        /**
         * End update operation. This will store the changes to the associated
         * <code>PersistenceManager</code>. At the end of this operation, an
         * eventual read or write lock on the item state manager will have
         * been released.
         * @throws ItemStateException if some error occurs
         */
        public void end() throws ItemStateException {
            boolean succeeded = false;

            try {
                /* Store items in the underlying persistence manager */
                long t0 = System.currentTimeMillis();
                persistMgr.store(shared);
                succeeded = true;
                long t1 = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug("persisting change log " + shared + " took " + (t1 - t0) + "ms");
                }
            } finally {
                if (!succeeded) {
                    cancel();
                }
            }

            try {
                /* Let the shared item listeners know about the change */
                shared.persisted();

                /* notify virtual providers about node references */
                for (int i = 0; i < virtualNodeReferences.length; i++) {
                    List virtualRefs = virtualNodeReferences[i];
                    if (virtualRefs != null) {
                        for (Iterator iter = virtualRefs.iterator(); iter.hasNext();) {
                            NodeReferences refs = (NodeReferences) iter.next();
                            virtualProviders[i].setNodeReferences(refs);
                        }
                    }
                }

                // downgrade to read lock
                acquireReadLock();
                rwLock.writeLock().release();
                holdingWriteLock = false;

                /* dispatch the events */
                events.dispatch();

            } finally {
                if (holdingWriteLock) {
                    // exception occured before downgrading lock
                    rwLock.writeLock().release();
                    holdingWriteLock = false;
                } else {
                    rwLock.readLock().release();
                }
            }
        }

        /**
         * Cancel update operation. At the end of this operation, the write lock
         * on the item state manager will have been released.
         */
        public void cancel() {
            try {
                local.disconnect();

                for (Iterator iter = shared.modifiedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    try {
                        state.copy(loadItemState(state.getId()));
                    } catch (ItemStateException e) {
                        state.discard();
                    }
                }
                for (Iterator iter = shared.deletedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    try {
                        state.copy(loadItemState(state.getId()));
                    } catch (ItemStateException e) {
                        state.discard();
                    }
                }
                for (Iterator iter = shared.addedStates(); iter.hasNext();) {
                    ItemState state = (ItemState) iter.next();
                    state.discard();
                }
            } finally {
                if (holdingWriteLock) {
                    rwLock.writeLock().release();
                    holdingWriteLock = false;
                }
            }
        }
    }

    /**
     * Begin update operation. This will return an object that can itself be
     * ended/cancelled.
     */
    public Update beginUpdate(ChangeLog local, EventStateCollectionFactory factory,
                              VirtualItemStateProvider virtualProvider)
            throws ReferentialIntegrityException, StaleItemStateException,
                   ItemStateException {

        Update update = new Update(local, factory, virtualProvider);
        update.begin();
        return update;
    }

    /**
     * Store modifications registered in a <code>ChangeLog</code>. The items
     * contained in the <tt>ChangeLog</tt> are not states returned by this
     * item state manager but rather must be reconnected to items provided
     * by this state manager.<p/>
     * After successfully storing the states the observation manager is informed
     * about the changes, if an observation manager is passed to this method.<p/>
     * NOTE: This method is not synchronized, because all methods it invokes
     * on instance members (such as {@link PersistenceManager#store} are
     * considered to be thread-safe. Should this ever change, the
     * synchronization status has to be re-examined.
     *
     * @param local   change log containing local items
     * @param factory event state collection factory
     * @throws ReferentialIntegrityException if a new or modified REFERENCE
     *                                       property refers to a non-existent
     *                                       target or if a removed node is still
     *                                       being referenced
     * @throws StaleItemStateException       if at least one of the affected item
     *                                       states has become stale
     * @throws ItemStateException            if another error occurs
     */
    public void update(ChangeLog local, EventStateCollectionFactory factory)
            throws ReferentialIntegrityException, StaleItemStateException,
                   ItemStateException {

        beginUpdate(local, factory, null).end();
    }

    //-------------------------------------------------------< implementation >
    /**
     * Create a new node state instance
     *
     * @param id         uuid
     * @param nodeTypeName node type name
     * @param parentId   parent UUID
     * @return new node state instance
     */
    private NodeState createInstance(NodeId id, QName nodeTypeName,
                                     NodeId parentId) {

        NodeState state = persistMgr.createNew(id);
        state.setNodeTypeName(nodeTypeName);
        state.setParentId(parentId);
        state.setStatus(ItemState.STATUS_NEW);
        state.addListener(this);

        return state;
    }

    /**
     * Create root node state
     *
     * @param rootNodeId root node id
     * @param ntReg        node type registry
     * @return root node state
     * @throws ItemStateException if an error occurs
     */
    private NodeState createRootNodeState(NodeId rootNodeId,
                                          NodeTypeRegistry ntReg)
            throws ItemStateException {

        NodeState rootState = createInstance(rootNodeId, QName.REP_ROOT, null);

        // FIXME need to manually setup root node by creating mandatory jcr:primaryType property
        // @todo delegate setup of root node to NodeTypeInstanceHandler

        // id of the root node's definition
        NodeDefId nodeDefId;
        // definition of jcr:primaryType property
        PropDef propDef;
        try {
            nodeDefId = ntReg.getRootNodeDef().getId();
            EffectiveNodeType ent = ntReg.getEffectiveNodeType(QName.REP_ROOT);
            propDef = ent.getApplicablePropertyDef(QName.JCR_PRIMARYTYPE,
                    PropertyType.NAME, false);
        } catch (NoSuchNodeTypeException nsnte) {
            String msg = "internal error: failed to create root node";
            log.error(msg, nsnte);
            throw new ItemStateException(msg, nsnte);
        } catch (ConstraintViolationException cve) {
            String msg = "internal error: failed to create root node";
            log.error(msg, cve);
            throw new ItemStateException(msg, cve);
        }
        rootState.setDefinitionId(nodeDefId);

        // create jcr:primaryType property
        rootState.addPropertyName(propDef.getName());

        PropertyState prop = createInstance(propDef.getName(), rootNodeId);
        prop.setValues(new InternalValue[]{InternalValue.create(QName.REP_ROOT)});
        prop.setType(propDef.getRequiredType());
        prop.setMultiValued(propDef.isMultiple());
        prop.setDefinitionId(propDef.getId());

        ChangeLog changeLog = new ChangeLog();
        changeLog.added(rootState);
        changeLog.added(prop);

        persistMgr.store(changeLog);
        changeLog.persisted();

        return rootState;
    }

    /**
     * Returns the item state for the given id without considering virtual
     * item state providers.
     */
    private ItemState getNonVirtualItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        // check cache; synchronized to ensure an entry is not created twice.
        synchronized (cache) {
            ItemState state = cache.retrieve(id);
            if (state == null) {
                // not found in cache, load from persistent storage
                state = loadItemState(id);
                state.setStatus(ItemState.STATUS_EXISTING);
                // put it in cache
                cache.cache(state);
                // register as listener
                state.addListener(this);
            }
            return state;
        }
    }

    /**
     * Checks if this item state manager has the given item state without
     * considering the virtual item state managers.
     */
    private boolean hasNonVirtualItemState(ItemId id) {
        if (cache.isCached(id)) {
            return true;
        }

        try {
            if (id.denotesNode()) {
                return persistMgr.exists((NodeId) id);
            } else {
                return persistMgr.exists((PropertyId) id);
            }
        } catch (ItemStateException ise) {
            return false;
        }
    }

    /**
     * Create a new node state instance
     *
     * @param other other state associated with new instance
     * @return new node state instance
     */
    private ItemState createInstance(ItemState other) {
        if (other.isNode()) {
            NodeState ns = (NodeState) other;
            return createInstance(ns.getNodeId(), ns.getNodeTypeName(), ns.getParentId());
        } else {
            PropertyState ps = (PropertyState) other;
            return createInstance(ps.getName(), ps.getParentId());
        }
    }

    /**
     * Create a new property state instance
     *
     * @param propName   property name
     * @param parentId parent Id
     * @return new property state instance
     */
    private PropertyState createInstance(QName propName, NodeId parentId) {
        PropertyState state = persistMgr.createNew(new PropertyId(parentId, propName));
        state.setStatus(ItemState.STATUS_NEW);
        state.addListener(this);

        return state;
    }

    /**
     * Load item state from persistent storage.
     *
     * @param id item id
     * @return item state
     */
    private ItemState loadItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        ItemState state;
        if (id.denotesNode()) {
            state = persistMgr.load((NodeId) id);
        } else {
            state = persistMgr.load((PropertyId) id);
        }
        return state;
    }

    /**
     * Determines whether the specified node is <i>referenceable</i>, i.e.
     * whether the mixin type <code>mix:referenceable</code> is either
     * directly assigned or indirectly inherited.
     *
     * @param state node state to check
     * @return true if the specified node is <i>referenceable</i>, false otherwise.
     * @throws ItemStateException if an error occurs
     */
    private boolean isReferenceable(NodeState state) throws ItemStateException {
        // shortcut: check some wellknown built-in types first
        QName primary = state.getNodeTypeName();
        Set mixins = state.getMixinTypeNames();
        if (mixins.contains(QName.MIX_REFERENCEABLE)
                || mixins.contains(QName.MIX_VERSIONABLE)
                || primary.equals(QName.NT_RESOURCE)) {
            return true;
        }
        // build effective node type
        QName[] types = new QName[mixins.size() + 1];
        mixins.toArray(types);
        // primary type
        types[types.length - 1] = primary;
        try {
            return ntReg.getEffectiveNodeType(types).includesNodeType(QName.MIX_REFERENCEABLE);
        } catch (NodeTypeConflictException ntce) {
            String msg = "internal error: failed to build effective node type for node "
                    + state.getNodeId();
            log.debug(msg);
            throw new ItemStateException(msg, ntce);
        } catch (NoSuchNodeTypeException nsnte) {
            String msg = "internal error: failed to build effective node type for node "
                    + state.getNodeId();
            log.debug(msg);
            throw new ItemStateException(msg, nsnte);
        }
    }

    /**
     * Updates the target node references collections based on the modifications
     * in the change log (i.e. added/removed/modified <code>REFERENCE</code>
     * properties).
     * <p/>
     * <b>Important node:</b> For consistency reasons this method must only be
     * called <i>once</i> per change log and the change log should not be modified
     * anymore afterwards.
     *
     * @param changes change log
     * @param virtualProvider virtual provider that may already contain a
     *                        node references object
     * @throws ItemStateException if an error occurs
     */
    protected void updateReferences(ChangeLog changes,
                                    VirtualItemStateProvider virtualProvider)
            throws ItemStateException {

        // process added REFERENCE properties
        for (Iterator iter = changes.addedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState prop = (PropertyState) state;
                if (prop.getType() == PropertyType.REFERENCE) {
                    // this is a new REFERENCE property:
                    // add the new 'reference'
                    InternalValue[] vals = prop.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        NodeReferencesId refsId = new NodeReferencesId(
                                (UUID) vals[i].internalValue());
                        if (virtualProvider != null &&
                                virtualProvider.hasNodeReferences(refsId)) {
                            continue;
                        }
                        NodeReferences refs =
                                getOrCreateNodeReferences(refsId, changes);
                        // add reference
                        refs.addReference(prop.getPropertyId());
                        // update change log
                        changes.modified(refs);
                    }
                }
            }
        }

        // process modified REFERENCE properties
        for (Iterator iter = changes.modifiedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState newProp = (PropertyState) state;
                PropertyState oldProp =
                        (PropertyState) getItemState(state.getId());
                // check old type
                if (oldProp.getType() == PropertyType.REFERENCE) {
                    // this is a modified REFERENCE property:
                    // remove the old 'reference' from the target
                    InternalValue[] vals = oldProp.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        NodeReferencesId refsId = new NodeReferencesId(
                                (UUID) vals[i].internalValue());
                        if (virtualProvider != null &&
                                virtualProvider.hasNodeReferences(refsId)) {
                            continue;
                        }
                        // either get node references from change log or load from
                        // persistence manager
                        NodeReferences refs = changes.get(refsId);
                        if (refs == null) {
                            refs = getNodeReferences(refsId);
                        }
                        // remove reference
                        refs.removeReference(oldProp.getPropertyId());
                        // update change log
                        changes.modified(refs);
                    }
                }
                // check new type
                if (newProp.getType() == PropertyType.REFERENCE) {
                    // this is a modified REFERENCE property:
                    // add the new 'reference' to the target
                    InternalValue[] vals = newProp.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        NodeReferencesId refsId = new NodeReferencesId(
                                (UUID) vals[i].internalValue());
                        if (virtualProvider != null &&
                                virtualProvider.hasNodeReferences(refsId)) {
                            continue;
                        }
                        NodeReferences refs =
                                getOrCreateNodeReferences(refsId, changes);
                        // add reference
                        refs.addReference(newProp.getPropertyId());
                        // update change log
                        changes.modified(refs);
                    }
                }
            }
        }

        // process removed REFERENCE properties
        for (Iterator iter = changes.deletedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (!state.isNode()) {
                PropertyState prop = (PropertyState) state;
                if (prop.getType() == PropertyType.REFERENCE) {
                    // this is a removed REFERENCE property:
                    // remove the 'reference' from the target
                    InternalValue[] vals = prop.getValues();
                    for (int i = 0; vals != null && i < vals.length; i++) {
                        NodeReferencesId refsId = new NodeReferencesId(
                                (UUID) vals[i].internalValue());
                        if (virtualProvider != null &&
                                virtualProvider.hasNodeReferences(refsId)) {
                            continue;
                        }
                        // either get node references from change log or
                        // load from persistence manager
                        NodeReferences refs = changes.get(refsId);
                        if (refs == null) {
                            refs = getNodeReferences(refsId);
                        }
                        // remove reference
                        refs.removeReference(prop.getPropertyId());
                        // update change log
                        changes.modified(refs);
                    }
                }
            }
        }
    }

    /**
     * Returns a node references object using the following rules:<p/>
     * <ul>
     * <li>1. return a modified instance from the change log (if one exists)</li>
     * <li>2. return an existing instance from <i>this</i> item state manager
     * (if one exists)</li>
     * <li>3. create and return a new instance</li>
     * </ul>
     *
     * @param refsId  node references id
     * @param changes change log
     * @return a node references object
     * @throws ItemStateException if an error occurs
     */
    private NodeReferences getOrCreateNodeReferences(NodeReferencesId refsId,
                                                     ChangeLog changes)
            throws ItemStateException {
        // check change log
        NodeReferences refs = changes.get(refsId);
        if (refs == null) {
            // not yet in change log:
            // either load existing or create new
            if (hasNodeReferences(refsId)) {
                refs = getNodeReferences(refsId);
            } else {
                refs = new NodeReferences(refsId);
            }
        }
        return refs;
    }

    /**
     * Verifies that
     * <ul>
     * <li>no referenceable nodes are deleted if they are still being referenced</li>
     * <li>targets of modified node references exist</li>
     * </ul>
     *
     * @param changes change log
     * @throws ReferentialIntegrityException if a new or modified REFERENCE
     *                                       property refers to a non-existent
     *                                       target or if a removed node is still
     *                                       being referenced
     * @throws ItemStateException            if another error occurs
     */
    protected void checkReferentialIntegrity(ChangeLog changes)
            throws ReferentialIntegrityException, ItemStateException {

        // check whether removed referenceable nodes are still being referenced
        for (Iterator iter = changes.deletedStates(); iter.hasNext();) {
            ItemState state = (ItemState) iter.next();
            if (state.isNode()) {
                NodeState node = (NodeState) state;
                if (isReferenceable(node)) {
                    NodeReferencesId refsId = new NodeReferencesId(node.getNodeId());
                    // either get node references from change log or
                    // load from persistence manager
                    NodeReferences refs = changes.get(refsId);
                    if (refs == null) {
                        if (!hasNodeReferences(refsId)) {
                            continue;
                        }
                        refs = getNodeReferences(refsId);
                    }
                    // in some versioning operations (such as restore) a node
                    // may actually be deleted and then again added with the
                    // same UUID, i.e. the node is still referenceable.
                    if (refs.hasReferences() && !changes.has(node.getNodeId())) {
                        String msg = node.getNodeId()
                                + ": the node cannot be removed because it is still being referenced.";
                        log.debug(msg);
                        throw new ReferentialIntegrityException(msg);
                    }
                }
            }
        }

        // check whether targets of modified node references exist
        for (Iterator iter = changes.modifiedRefs(); iter.hasNext();) {
            NodeReferences refs = (NodeReferences) iter.next();
            NodeId id = refs.getTargetId();
            // no need to check existence of target if there are no references
            if (refs.hasReferences()) {
                // please note:
                // virtual providers are indirectly checked via 'hasItemState()'
                if (!changes.has(id) && !hasItemState(id)) {
                    String msg = "Target node " + id
                            + " of REFERENCE property does not exist";
                    log.debug(msg);
                    throw new ReferentialIntegrityException(msg);
                }
            }
        }
    }

    /**
     * Acquires the read lock on this item state manager.
     *
     * @throws ItemStateException if the read lock cannot be acquired.
     */
    private void acquireReadLock() throws ItemStateException {
        try {
            rwLock.readLock().acquire();
        } catch (InterruptedException e) {
            throw new ItemStateException("Interrupted while acquiring read lock");
        }
    }

    /**
     * Acquires the write lock on this item state manager.
     *
     * @throws ItemStateException if the write lock cannot be acquired.
     */
    private void acquireWriteLock() throws ItemStateException {
        try {
            rwLock.writeLock().acquire();
        } catch (InterruptedException e) {
            throw new ItemStateException("Interrupted while acquiring write lock");
        }
    }
}
