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
package org.apache.jackrabbit.core;

import org.apache.commons.collections.ReferenceMap;
import org.apache.jackrabbit.core.nodetype.NodeDefId;
import org.apache.jackrabbit.core.nodetype.NodeDefinitionImpl;
import org.apache.jackrabbit.core.nodetype.PropDefId;
import org.apache.jackrabbit.core.nodetype.PropertyDefinitionImpl;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.version.InternalVersion;
import org.apache.jackrabbit.core.version.InternalVersionHistory;
import org.apache.jackrabbit.core.version.VersionHistoryImpl;
import org.apache.jackrabbit.core.version.VersionImpl;
import org.apache.log4j.Logger;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.PropertyDefinition;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * There's one <code>ItemManager</code> instance per <code>Session</code>
 * instance. It is the factory for <code>Node</code> and <code>Property</code>
 * instances.
 * <p/>
 * The <code>ItemManager</code>'s responsabilities are:
 * <ul>
 * <li>providing access to <code>Item</code> instances by <code>ItemId</code>
 * whereas <code>Node</code> and <code>Item</code> are only providing relative access.
 * <li>returning the instance of an existing <code>Node</code> or <code>Property</code>,
 * given its absolute path.
 * <li>creating the per-session instance of a <code>Node</code>
 * or <code>Property</code> that doesn't exist yet and needs to be created first.
 * <li>guaranteeing that there aren't multiple instances representing the same
 * <code>Node</code> or <code>Property</code> associated with the same
 * <code>Session</code> instance.
 * <li>maintaining a cache of the item instances it created.
 * <li>respecting access rights of associated <code>Session</code> in all methods.
 * </ul>
 * <p/>
 * If the parent <code>Session</code> is an <code>XASession</code>, there is
 * one <code>ItemManager</code> instance per started global transaction.
 */
public class ItemManager implements ItemLifeCycleListener, Constants {

    private static Logger log = Logger.getLogger(ItemManager.class);

    private final NodeDefinition rootNodeDef;
    private final NodeId rootNodeId;

    private final SessionImpl session;

    private final ItemStateManager itemStateProvider;
    private final HierarchyManager hierMgr;

    private NodeImpl root;

    /**
     * A cache for item instances created by this <code>ItemManager</code>
     */
    private Map itemCache;

    /**
     * Creates a new per-session instance <code>ItemManager</code> instance.
     *
     * @param itemStateProvider the item state provider associated with
     *                          the new instance
     * @param session           the session associated with the new instance
     * @param rootNodeDef       the definition of the root node
     * @param rootNodeUUID      the UUID of the root node
     */
    ItemManager(ItemStateManager itemStateProvider, HierarchyManager hierMgr,
                SessionImpl session, NodeDefinition rootNodeDef,
                String rootNodeUUID) {
        this.itemStateProvider = itemStateProvider;
        this.hierMgr = hierMgr;
        this.session = session;
        this.rootNodeDef = rootNodeDef;
        rootNodeId = new NodeId(rootNodeUUID);
        // setup item cache with weak references to items
        itemCache = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.WEAK);
    }

    /**
     * Returns the root node instance of the repository.
     *
     * @return the root node.
     * @throws RepositoryException
     */
    private synchronized NodeImpl getRoot() throws RepositoryException {
        // lazy instantiation of root node
        // to avoid chicken & egg kind of problems
        if (root == null) {
            try {
                NodeState rootState = (NodeState) itemStateProvider.getItemState(rootNodeId);
                // keep a hard reference to root node
                root = createNodeInstance(rootState, rootNodeDef);
            } catch (ItemStateException ise) {
                String msg = "failed to retrieve state of root node";
                log.debug(msg);
                throw new RepositoryException(msg, ise);
            }
        }
        return root;
    }

    /**
     * Dumps the state of this <code>ItemManager</code> instance
     * (used for diagnostic purposes).
     *
     * @param ps
     * @throws RepositoryException
     */
    void dump(PrintStream ps) throws RepositoryException {
        ps.println("ItemManager (" + this + ")");
        ps.println();
        ps.println("Items in cache:");
        ps.println();
        Iterator iter = itemCache.keySet().iterator();
        while (iter.hasNext()) {
            ItemId id = (ItemId) iter.next();
            ItemImpl item = (ItemImpl) itemCache.get(id);
            if (item.isNode()) {
                ps.print("Node: ");
            } else {
                ps.print("Property: ");
            }
            if (item.isTransient()) {
                ps.print("transient ");
            } else {
                ps.print("          ");
            }
            ps.println(id + "\t" + item.getPath() + " (" + item + ")");
        }
    }

    /**
     * Disposes this <code>ItemManager</code> and frees resources.
     */
    void dispose() {
        itemCache.clear();
    }

    private NodeDefinition getDefinition(NodeState state)
            throws RepositoryException {
        NodeDefId defId = state.getDefinitionId();
        NodeDefinitionImpl def = session.getNodeTypeManager().getNodeDefinition(defId);
        if (def == null) {
            log.warn("node at " + safeGetJCRPath(state.getId()) + " has invalid definitionId (" + defId + ")");

            // fallback: try finding applicable definition
            NodeId parentId = new NodeId(state.getParentUUID());
            NodeImpl parent = (NodeImpl) getItem(parentId);
            NodeState parentState = (NodeState) parent.getItemState();
            NodeState.ChildNodeEntry cne = (NodeState.ChildNodeEntry) parentState.getChildNodeEntries(state.getUUID()).get(0);
            def = parent.getApplicableChildNodeDefinition(cne.getName(), state.getNodeTypeName());
            state.setDefinitionId(def.unwrap().getId());
        }
        return def;
    }

    private PropertyDefinition getDefinition(PropertyState state)
            throws RepositoryException {
        PropDefId defId = state.getDefinitionId();
        PropertyDefinitionImpl def = session.getNodeTypeManager().getPropertyDefinition(defId);
        if (def == null) {
            log.warn("property at " + safeGetJCRPath(state.getId()) + " has invalid definitionId (" + defId + ")");

            // fallback: try finding applicable definition
            NodeId parentId = new NodeId(state.getParentUUID());
            NodeImpl parent = (NodeImpl) getItem(parentId);
            def = parent.getApplicablePropertyDefinition(state.getName(), state.getType(), state.isMultiValued());
            state.setDefinitionId(def.unwrap().getId());
        }
        return def;
    }

    /**
     * Retrieves state of item with given <code>id</code>. If the specified item
     * doesn't exist an <code>ItemNotFoundException</code> will be thrown.
     * If the item exists but the current session is not granted read access an
     * <code>AccessDeniedException</code> will be thrown.
     *
     * @param id id of item to be retrieved
     * @return state state of said item
     * @throws ItemNotFoundException if no item with given <code>id</code> exists
     * @throws AccessDeniedException if the current session is not allowed to
     *                               read the said item
     * @throws RepositoryException   if another error occurs
     */
    private ItemState getItemState(ItemId id)
            throws ItemNotFoundException, AccessDeniedException,
            RepositoryException {
        // check privileges
        if (!session.getAccessManager().isGranted(id, AccessManager.READ)) {
            // clear cache
            ItemImpl item = retrieveItem(id);
            if (item != null) {
                evictItem(id);
            }
            throw new AccessDeniedException("cannot read item " + id);
        }

        try {
            return itemStateProvider.getItemState(id);
        } catch (NoSuchItemStateException nsise) {
            String msg = "no such item: " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg);
        } catch (ItemStateException ise) {
            String msg = "failed to retrieve item state of " + id;
            log.error(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    //--------------------------------------------------< item access methods >
    /**
     * Checks if the item with the given path exists.
     *
     * @param path path to the item to be checked
     * @return true if the specified item exists
     */
    public boolean itemExists(Path path) {
        try {
            // check sanity of session
            session.sanityCheck();

            ItemId id = hierMgr.resolvePath(path);

            // check if state exists for the given item
            if (!itemStateProvider.hasItemState(id)) {
                return false;
            }

            // check privileges
            if (!session.getAccessManager().isGranted(id, AccessManager.READ)) {
                // clear cache
                if (isCached(id)) {
                    evictItem(id);
                }
                // item exists but the session has not been granted read access
                return false;
            }
            return true;
        } catch (PathNotFoundException pnfe) {
            return false;
        } catch (ItemNotFoundException infe) {
            return false;
        } catch (RepositoryException re) {
            return false;
        }
    }

    /**
     * Checks if the item with the given id exists.
     *
     * @param id id of the item to be checked
     * @return true if the specified item exists
     */
    public boolean itemExists(ItemId id) {
        try {
            // check sanity of session
            session.sanityCheck();

            // check if state exists for the given item
            if (!itemStateProvider.hasItemState(id)) {
                return false;
            }

            // check privileges
            if (!session.getAccessManager().isGranted(id, AccessManager.READ)) {
                // clear cache
                if (isCached(id)) {
                    evictItem(id);
                }
                // item exists but the session has not been granted read access
                return false;
            }
            return true;
        } catch (ItemNotFoundException infe) {
            return false;
        } catch (RepositoryException re) {
            return false;
        }
    }

    /**
     * @return
     * @throws RepositoryException
     */
    NodeImpl getRootNode() throws RepositoryException {
        return getRoot();
    }

    /**
     * @param path
     * @return
     * @throws PathNotFoundException
     * @throws AccessDeniedException
     * @throws RepositoryException
     */
    public synchronized ItemImpl getItem(Path path)
            throws PathNotFoundException, AccessDeniedException, RepositoryException {
        ItemId id = hierMgr.resolvePath(path);
        try {
            return getItem(id);
        } catch (ItemNotFoundException infe) {
            throw new PathNotFoundException(safeGetJCRPath(path));
        }
    }

    /**
     * @param id
     * @return
     * @throws RepositoryException
     */
    public synchronized ItemImpl getItem(ItemId id)
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check sanity of session
        session.sanityCheck();

        // check privileges
        if (!session.getAccessManager().isGranted(id, AccessManager.READ)) {
            // clear cache
            if (isCached(id)) {
                evictItem(id);
            }
            throw new AccessDeniedException("cannot read item " + id);
        }

        // check cache
        if (isCached(id)) {
            return retrieveItem(id);
        }

        // shortcut
        if (id.denotesNode() && id.equals(rootNodeId)) {
            return getRoot();
        }

        // create instance of item using its state object
        return createItemInstance(id);
    }

    /**
     * @param parentId
     * @return
     * @throws ItemNotFoundException
     * @throws AccessDeniedException
     * @throws RepositoryException
     */
    synchronized boolean hasChildNodes(NodeId parentId)
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check sanity of session
        session.sanityCheck();

        ItemState state = getItemState(parentId);
        if (!state.isNode()) {
            String msg = "can't list child nodes of property " + parentId;
            log.debug(msg);
            throw new RepositoryException(msg);
        }
        NodeState nodeState = (NodeState) state;
        Iterator iter = nodeState.getChildNodeEntries().iterator();

        while (iter.hasNext()) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) iter.next();
            NodeId id = new NodeId(entry.getUUID());
            // check read access
            if (session.getAccessManager().isGranted(id, AccessManager.READ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param parentId
     * @return
     * @throws ItemNotFoundException
     * @throws AccessDeniedException
     * @throws RepositoryException
     */
    synchronized NodeIterator getChildNodes(NodeId parentId)
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check sanity of session
        session.sanityCheck();

        ItemState state = getItemState(parentId);
        if (!state.isNode()) {
            String msg = "can't list child nodes of property " + parentId;
            log.debug(msg);
            throw new RepositoryException(msg);
        }
        NodeState nodeState = (NodeState) state;
        ArrayList childIds = new ArrayList();
        Iterator iter = nodeState.getChildNodeEntries().iterator();

        while (iter.hasNext()) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) iter.next();
            NodeId id = new NodeId(entry.getUUID());
            // check read access
            if (session.getAccessManager().isGranted(id, AccessManager.READ)) {
                childIds.add(id);
            }
        }

        return new LazyItemIterator(this, childIds);
    }

    /**
     * @param parentId
     * @return
     * @throws ItemNotFoundException
     * @throws AccessDeniedException
     * @throws RepositoryException
     */
    synchronized boolean hasChildProperties(NodeId parentId)
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check sanity of session
        session.sanityCheck();

        ItemState state = getItemState(parentId);
        if (!state.isNode()) {
            String msg = "can't list child properties of property " + parentId;
            log.debug(msg);
            throw new RepositoryException(msg);
        }
        NodeState nodeState = (NodeState) state;
        Iterator iter = nodeState.getPropertyEntries().iterator();

        while (iter.hasNext()) {
            NodeState.PropertyEntry entry = (NodeState.PropertyEntry) iter.next();

            PropertyId id = new PropertyId(parentId.getUUID(), entry.getName());
            // check read access
            if (session.getAccessManager().isGranted(id, AccessManager.READ)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param parentId
     * @return
     * @throws ItemNotFoundException
     * @throws AccessDeniedException
     * @throws RepositoryException
     */
    synchronized PropertyIterator getChildProperties(NodeId parentId)
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check sanity of session
        session.sanityCheck();

        ItemState state = getItemState(parentId);
        if (!state.isNode()) {
            String msg = "can't list child properties of property " + parentId;
            log.debug(msg);
            throw new RepositoryException(msg);
        }
        NodeState nodeState = (NodeState) state;
        ArrayList childIds = new ArrayList();
        Iterator iter = nodeState.getPropertyEntries().iterator();

        while (iter.hasNext()) {
            NodeState.PropertyEntry entry = (NodeState.PropertyEntry) iter.next();
            PropertyId id = new PropertyId(parentId.getUUID(), entry.getName());
            // check read access
            if (session.getAccessManager().isGranted(id, AccessManager.READ)) {
                childIds.add(id);
            }
        }

        return new LazyItemIterator(this, childIds);
    }

    //-------------------------------------------------< item factory methods >
    private ItemImpl createItemInstance(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        // create instance of item using its state object
        ItemImpl item;
        ItemState state;
        try {
            state = itemStateProvider.getItemState(id);
        } catch (NoSuchItemStateException nsise) {
            throw new ItemNotFoundException(id.toString());
        } catch (ItemStateException ise) {
            String msg = "failed to retrieve item state of item " + id;
            log.error(msg, ise);
            throw new RepositoryException(msg, ise);
        }

        if (state.isNode()) {
            item = createNodeInstance((NodeState) state);
        } else {
            item = createPropertyInstance((PropertyState) state);
        }
        return item;
    }

    NodeImpl createNodeInstance(NodeState state, NodeDefinition def)
            throws RepositoryException {
        NodeId id = new NodeId(state.getUUID());
        // we want to be informed on life cycle changes of the new node object
        // in order to maintain item cache consistency
        ItemLifeCycleListener[] listeners = new ItemLifeCycleListener[]{this};

        // check special nodes
        if (state.getNodeTypeName().equals(NT_VERSION)) {
            InternalVersion version =
                    session.getVersionManager().getVersion(state.getUUID());
            return new VersionImpl(this, session, id, state, def, listeners, version);

        } else if (state.getNodeTypeName().equals(NT_VERSIONHISTORY)) {
            InternalVersionHistory history =
                    session.getVersionManager().getVersionHistory(state.getUUID());
            return new VersionHistoryImpl(this, session, id, state, def, listeners, history);

        } else {
            // create node object
            return new NodeImpl(this, session, id, state, def, listeners);
        }

    }

    NodeImpl createNodeInstance(NodeState state) throws RepositoryException {
        // 1. get definition of the specified node
        NodeDefinition def = getDefinition(state);
        // 2. create instance
        return createNodeInstance(state, def);
    }

    PropertyImpl createPropertyInstance(PropertyState state,
                                        PropertyDefinition def) {
        PropertyId id = new PropertyId(state.getParentUUID(), state.getName());
        // we want to be informed on life cycle changes of the new property object
        // in order to maintain item cache consistency
        ItemLifeCycleListener[] listeners = new ItemLifeCycleListener[]{this};
        // create property object
        PropertyImpl prop = new PropertyImpl(this, session, id, state, def, listeners);
        return prop;
    }

    PropertyImpl createPropertyInstance(PropertyState state)
            throws RepositoryException {
        // 1. get definition for the specified property
        PropertyDefinition def = getDefinition(state);
        // 2. create instance
        return createPropertyInstance(state, def);
    }

    //---------------------------------------------------< item cache methods >
    /**
     * Checks if there's a cache entry for the specified id.
     *
     * @param id the id to be checked
     * @return true if there's a corresponding cache entry, otherwise false.
     */
    private boolean isCached(ItemId id) {
        return itemCache.containsKey(id);
    }

    /**
     * Returns an item reference from the cache.
     *
     * @param id id of the item that should be retrieved.
     * @return the item reference stored in the corresponding cache entry
     *         or <code>null</code> if there's no corresponding cache entry.
     */
    private ItemImpl retrieveItem(ItemId id) {
        return (ItemImpl) itemCache.get(id);
    }

    /**
     * Puts the reference of an item in the cache with
     * the item's path as the key.
     *
     * @param item the item to cache
     */
    private void cacheItem(ItemImpl item) {
        ItemId id = item.getId();
        if (itemCache.containsKey(id)) {
            log.warn("overwriting cached item " + id);
        }
        if (log.isDebugEnabled()) {
            log.debug("caching item " + id);
        }
        itemCache.put(id, item);
    }

    /**
     * Removes a cache entry for a specific item.
     *
     * @param id id of the item to remove from the cache
     */
    private void evictItem(ItemId id) {
        if (log.isDebugEnabled()) {
            log.debug("removing item " + id + " from cache");
        }
        itemCache.remove(id);
    }

    //-------------------------------------------------< misc. helper methods >
    /**
     * Failsafe conversion of internal <code>Path</code> to JCR path for use in
     * error messages etc.
     *
     * @param path path to convert
     * @return JCR path
     */
    String safeGetJCRPath(Path path) {
        try {
            return path.toJCRPath(session.getNamespaceResolver());
        } catch (NoPrefixDeclaredException npde) {
            log.error("failed to convert " + path.toString() + " to JCR path.");
            // return string representation of internal path as a fallback
            return path.toString();
        }
    }

    /**
     * Failsafe translation of internal <code>ItemId</code> to JCR path for use in
     * error messages etc.
     *
     * @param id path to convert
     * @return JCR path
     */
    String safeGetJCRPath(ItemId id) {
        try {
            return safeGetJCRPath(hierMgr.getPath(id));
        } catch (RepositoryException re) {
            log.error(id + ": failed to determine path to");
            // return string representation if id as a fallback
            return id.toString();
        }
    }

    //------------------------------------------------< ItemLifeCycleListener >
    /**
     * {@inheritDoc}
     */
    public void itemCreated(ItemImpl item) {
        if (log.isDebugEnabled()) {
            log.debug("created item " + item.getId());
        }
        // add instance to cache
        cacheItem(item);
    }

    /**
     * {@inheritDoc}
     */
    public void itemInvalidated(ItemId id, ItemImpl item) {
        if (log.isDebugEnabled()) {
            log.debug("invalidated item " + id);
        }
        // remove instance from cache
        evictItem(id);
    }

    /**
     * {@inheritDoc}
     */
    public void itemDestroyed(ItemId id, ItemImpl item) {
        if (log.isDebugEnabled()) {
            log.debug("destroyed item " + id);
        }
        // we're no longer interested in this item
        item.removeLifeCycleListener(this);
        // remove instance from cache
        evictItem(id);
    }
}
