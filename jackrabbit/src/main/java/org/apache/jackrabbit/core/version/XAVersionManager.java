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
package org.apache.jackrabbit.core.version;

import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.TransactionContext;
import org.apache.jackrabbit.core.TransactionException;
import org.apache.jackrabbit.core.InternalXAResource;
import org.apache.jackrabbit.core.observation.EventStateCollectionFactory;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.XAItemStateManager;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.ChangeLog;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.core.virtual.VirtualPropertyState;
import org.apache.jackrabbit.core.virtual.VirtualNodeState;
import org.apache.jackrabbit.name.QName;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Implementation of a {@link VersionManager} that works in an XA environment.
 * Works as a filter between a version manager client and the global version
 * manager.
 */
public class XAVersionManager extends AbstractVersionManager
        implements VirtualItemStateProvider, InternalXAResource {

    /**
     * Attribute name for associated change log.
     */
    private static final String CHANGE_LOG_ATTRIBUTE_NAME = "XAVersionManager.ChangeLog";

    /**
     * Attribute name for items.
     */
    private static final String ITEMS_ATTRIBUTE_NAME = "VersionItems";

    /**
     * Repository version manager.
     */
    private final VersionManagerImpl vMgr;

    /**
     * Node type registry.
     */
    private NodeTypeRegistry ntReg;

    /**
     * Items that have been modified and are part of the XA environment.
     */
    private Map xaItems;

    /**
     * Creates a new instance of this class.
     */
    public XAVersionManager(VersionManagerImpl vMgr, NodeTypeRegistry ntReg,
                            EventStateCollectionFactory factory)
            throws RepositoryException {

        this.vMgr = vMgr;
        this.ntReg = ntReg;
        this.stateMgr = new XAItemStateManager(vMgr.getSharedStateMgr(),
                factory, CHANGE_LOG_ATTRIBUTE_NAME);

        NodeState state;
        try {
            state = (NodeState) stateMgr.getItemState(vMgr.getHistoryRootId());
        } catch (ItemStateException e) {
            throw new RepositoryException("Unable to retrieve history root", e);
        }
        this.historyRoot = new NodeStateEx(stateMgr, ntReg, state, QName.JCR_VERSIONSTORAGE);
    }

    //-------------------------------------------------------< VersionManager >

    /**
     * {@inheritDoc}
     */
    public VirtualItemStateProvider getVirtualItemStateProvider() {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public VersionHistory createVersionHistory(Session session, NodeState node)
            throws RepositoryException {

        if (isInXA()) {
            InternalVersionHistory history = createVersionHistory(node);
            xaItems.put(history.getId(), history);
            return (VersionHistory) session.getNodeByUUID(history.getId());
        }
        return vMgr.createVersionHistory(session, node);
    }

    /**
     * {@inheritDoc}
     */
    public Version checkin(NodeImpl node) throws RepositoryException {
        if (isInXA()) {
            String histUUID = node.getProperty(QName.JCR_VERSIONHISTORY).getString();
            InternalVersion version = checkin(
                    (InternalVersionHistoryImpl) getVersionHistory(histUUID), node);
            return (Version) node.getSession().getNodeByUUID(version.getId());
        }
        return vMgr.checkin(node);
    }

    /**
     * {@inheritDoc}
     */
    public void removeVersion(VersionHistory history, QName versionName)
            throws RepositoryException {

        if (isInXA()) {
            InternalVersionHistoryImpl vh = (InternalVersionHistoryImpl)
                    ((AbstractVersionHistory) history).getInternalVersionHistory();
            removeVersion(vh, versionName);
            return;
        }
        vMgr.removeVersion(history, versionName);
    }

    /**
     * {@inheritDoc}
     */
    public Version setVersionLabel(VersionHistory history, QName version,
                                   QName label, boolean move)
            throws RepositoryException {

        if (isInXA()) {
            InternalVersionHistoryImpl vh = (InternalVersionHistoryImpl)
                    ((AbstractVersionHistory) history).getInternalVersionHistory();
            InternalVersion v = setVersionLabel(vh, version, label, move);
            if (v == null) {
                return null;
            } else {
                return (Version) history.getSession().getNodeByUUID(v.getId());
            }
        }
        return vMgr.setVersionLabel(history, version, label, move);
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws Exception {
    }

    //---------------------------------------------< VirtualItemStateProvider >

    /**
     * {@inheritDoc}
     */
    public boolean isVirtualRoot(ItemId id) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getVirtualRootId() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public VirtualPropertyState createPropertyState(VirtualNodeState parent,
                                                    QName name, int type,
                                                    boolean multiValued)
            throws RepositoryException {

        throw new IllegalStateException("Read-only");
    }

    /**
     * {@inheritDoc}
     */
    public VirtualNodeState createNodeState(VirtualNodeState parent, QName name,
                                            String uuid, QName nodeTypeName)
            throws RepositoryException {

        throw new IllegalStateException("Read-only");
    }

    /**
     * {@inheritDoc}
     */
    public boolean setNodeReferences(NodeReferences refs) {
        ChangeLog changeLog = ((XAItemStateManager) stateMgr).getChangeLog();
        if (changeLog != null) {
            changeLog.modified(refs);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Return item states for changes only. Global version manager will return
     * other items.
     */
    public ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        ChangeLog changeLog = ((XAItemStateManager) stateMgr).getChangeLog();
        if (changeLog != null) {
            return changeLog.get(id);
        }
        throw new NoSuchItemStateException("State not in change log: " + id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasItemState(ItemId id) {
        ChangeLog changeLog = ((XAItemStateManager) stateMgr).getChangeLog();
        if (changeLog != null) {
            return changeLog.has(id);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public NodeReferences getNodeReferences(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {

        ChangeLog changeLog = ((XAItemStateManager) stateMgr).getChangeLog();
        if (changeLog != null) {
            return changeLog.get(id);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasNodeReferences(NodeReferencesId id) {
        ChangeLog changeLog = ((XAItemStateManager) stateMgr).getChangeLog();
        if (changeLog != null) {
            return changeLog.get(id) != null;
        }
        return false;
    }

    //-----------------------------------------------< AbstractVersionManager >

    /**
     * {@inheritDoc}
     */
     protected InternalVersionItem getItem(String uuid) throws RepositoryException {
        InternalVersionItem item = null;
        if (xaItems != null) {
            item = (InternalVersionItem) xaItems.get(uuid);
        }
        if (item == null) {
            item = vMgr.getItem(uuid);
        }
        return item;
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasItem(String uuid) {
        if (xaItems != null && xaItems.containsKey(uuid)) {
            return true;
        }
        return vMgr.hasItem(uuid);
    }

    /**
     * {@inheritDoc}
     */
    protected List getItemReferences(InternalVersionItem item) {
        return vMgr.getItemReferences(item);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Before modifying version history given, make a local copy of it.
     */
    protected InternalVersion checkin(InternalVersionHistoryImpl history,
                                      NodeImpl node)
            throws RepositoryException {

        if (history.getVersionManager() != this) {
            history = makeLocalCopy(history);
            xaItems.put(history.getId(), history);
        }
        return super.checkin(history, node);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Before modifying version history given, make a local copy of it.
     */
    protected void removeVersion(InternalVersionHistoryImpl history, QName name)
            throws VersionException, RepositoryException {

        if (history.getVersionManager() != this) {
            history = makeLocalCopy(history);
            xaItems.put(history.getId(), history);
        }
        super.removeVersion(history, name);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Before modifying version history given, make a local copy of it.
     */
    protected InternalVersion setVersionLabel(InternalVersionHistoryImpl history,
                                              QName version, QName label,
                                              boolean move)
            throws RepositoryException {

        if (history.getVersionManager() != this) {
            history = makeLocalCopy(history);
            xaItems.put(history.getId(), history);
        }
        return super.setVersionLabel(history, version, label, move);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Put the version object into our cache.
     */
    protected void versionCreated(InternalVersion version) {
        xaItems.put(version.getId(), version);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Remove the version object from our cache.
     */
    protected void versionDestroyed(InternalVersion version) {
        xaItems.remove(version.getId());
    }

    //-------------------------------------------------------------------< XA >

    /**
     * {@inheritDoc}
     */
    public void associate(TransactionContext tx) {
        ((XAItemStateManager) stateMgr).associate(tx);

        Map xaItems = null;
        if (tx != null) {
            xaItems = (Map) tx.getAttribute(ITEMS_ATTRIBUTE_NAME);
            if (xaItems == null) {
                xaItems = new HashMap();
                tx.setAttribute(ITEMS_ATTRIBUTE_NAME, xaItems);
            }
        }
        this.xaItems = xaItems;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delegate the call to our XA item state manager.
     */
    public void beforeOperation(TransactionContext tx) {
        ((XAItemStateManager) stateMgr).beforeOperation(tx);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delegate the call to our XA item state manager.
     */
    public void prepare(TransactionContext tx) throws TransactionException {
        ((XAItemStateManager) stateMgr).prepare(tx);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delegate the call to our XA item state manager. If successful, inform
     * global repository manager to update its caches.
     */
    public void commit(TransactionContext tx) throws TransactionException {
        ((XAItemStateManager) stateMgr).commit(tx);

        Map xaItems = (Map) tx.getAttribute(ITEMS_ATTRIBUTE_NAME);
        vMgr.itemsUpdated(xaItems.values());
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delegate the call to our XA item state manager.
     */
    public void rollback(TransactionContext tx) {
        ((XAItemStateManager) stateMgr).rollback(tx);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delegate the call to our XA item state manager.
     */
    public void afterOperation(TransactionContext tx) {
        ((XAItemStateManager) stateMgr).afterOperation(tx);
    }

    //-------------------------------------------------------< implementation >

    /**
     * Return a flag indicating whether this version manager is currently
     * associated with an XA transaction.
     */
    private boolean isInXA() {
        return xaItems != null;
    }

    /**
     * Make a local copy of an internal version item. This will recreate the
     * (global) version item with state information from our own state
     * manager.
     */
    private InternalVersionHistoryImpl makeLocalCopy(InternalVersionHistoryImpl history)
            throws RepositoryException {

        NodeState state;
        try {
            state = (NodeState) stateMgr.getItemState(new NodeId(history.getId()));
        } catch (ItemStateException e) {
            throw new RepositoryException("Unable to make local copy", e);
        }
        NodeStateEx stateEx = new NodeStateEx(stateMgr, ntReg, state, null);
        return new InternalVersionHistoryImpl(this, stateEx);
    }

    /**
     * Return a flag indicating whether an internal version item belongs to
     * a different XA environment.
     */
    boolean differentXAEnv(InternalVersionItemImpl item) {
        if (item.getVersionManager() == this) {
            if (xaItems == null || !xaItems.containsKey(item.getId())) {
                return true;
            }
        }
        return false;
    }
}
