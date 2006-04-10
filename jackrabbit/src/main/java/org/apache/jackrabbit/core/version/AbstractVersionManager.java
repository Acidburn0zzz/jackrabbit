/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
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

import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.LocalItemStateManager;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.uuid.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.Session;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import java.util.List;

/**
 * Base implementation of the {@link VersionManager} interface.
 */
abstract class AbstractVersionManager implements VersionManager {

    /**
     * Logger instance.
     */
    private static Logger log = LoggerFactory.getLogger(AbstractVersionManager.class);

    /**
     * State manager for the version storage.
     */
    protected LocalItemStateManager stateMgr;

    /**
     * Persistent root node of the version histories.
     */
    protected NodeStateEx historyRoot;

    //-------------------------------------------------------< VersionManager >

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersion(NodeId id) throws RepositoryException {
        InternalVersion v = (InternalVersion) getItem(id);
        if (v == null) {
            log.warn("Versioning item not found: " + id);
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersionHistory getVersionHistory(NodeId id)
            throws RepositoryException {

        return (InternalVersionHistory) getItem(id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasVersionHistory(NodeId id) {
        return hasItem(id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasVersion(NodeId id) {
        return hasItem(id);
    }

    //-------------------------------------------------------< implementation >

    /**
     * {@inheritDoc}
     */
    public VersionHistory getVersionHistory(Session session, NodeState node)
            throws RepositoryException {

        NodeId vhId = getVersionHistoryId(node);
        if (vhId == null) {
            return null;
        }
        return (VersionHistory) ((SessionImpl) session).getNodeById(vhId);
    }

    /**
     * Returns the item with the given persistent id. Subclass responsibility.
     * @param id the id of the item
     * @return version item
     * @throws RepositoryException if an error occurs
     */
    protected abstract InternalVersionItem getItem(NodeId id)
            throws RepositoryException;

    /**
     * Return a flag indicating if the item specified exists.
     * Subclass responsibility.
     * @param id the id of the item
     * @return <code>true</code> if the item exists;
     *         <code>false</code> otherwise
     */
    protected abstract boolean hasItem(NodeId id);

    /**
     * Returns the item references that reference the given version item.
     * Subclass responsiblity.
     * @param item version item
     * @return list of item references, may be empty.
     */
    protected abstract List getItemReferences(InternalVersionItem item);

    /**
     * Creates a new Version History.
     *
     * @param node the node for which the version history is to be initialized
     * @return the newly created version history.
     * @throws javax.jcr.RepositoryException
     */
    InternalVersionHistory createVersionHistory(NodeState node)
            throws RepositoryException {

        try {
            stateMgr.edit();
        } catch (IllegalStateException e) {
            throw new RepositoryException("Unable to start edit operation", e);
        }

        boolean succeeded = false;

        try {
            // create deep path
            String uuid = node.getNodeId().getUUID().toString();
            NodeStateEx root = historyRoot;
            for (int i = 0; i < 3; i++) {
                QName name = new QName(QName.NS_DEFAULT_URI, uuid.substring(i * 2, i * 2 + 2));
                if (!root.hasNode(name)) {
                    root.addNode(name, QName.REP_VERSIONSTORAGE, null, false);
                    root.store();
                }
                root = root.getNode(name, 1);
            }
            QName historyNodeName = new QName(QName.NS_DEFAULT_URI, uuid);
            if (root.hasNode(historyNodeName)) {
                // already exists
                return null;
            }

            // create new history node in the persistent state
            InternalVersionHistoryImpl hist = InternalVersionHistoryImpl.create(
                    this, root, new NodeId(UUID.randomUUID()), historyNodeName, node);

            // end update
            stateMgr.update();
            succeeded = true;

            log.info("Created new version history " + hist.getId() + " for " + node + ".");
            return hist;

        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                stateMgr.cancel();
            }
        }
    }

    /**
     * Returns the id of the version history associated with the given node
     * or <code>null</code> if that node doesn't have a version history.
     *
     * @param node the node whose version history's id is to be returned.
     * @return the the id of the version history associated with the given node
     *         or <code>null</code> if that node doesn't have a version history.
     * @throws javax.jcr.RepositoryException if an error occurs
     */
    NodeId getVersionHistoryId(NodeState node) throws RepositoryException {

        // build and traverse path
        String uuid = node.getNodeId().getUUID().toString();
        NodeStateEx n = historyRoot;
        for (int i = 0; i < 3; i++) {
            QName name = new QName(QName.NS_DEFAULT_URI, uuid.substring(i * 2, i * 2 + 2));
            if (!n.hasNode(name)) {
                return null;
            }
            n = n.getNode(name, 1);
        }
        QName historyNodeName = new QName(QName.NS_DEFAULT_URI, uuid);
        if (!n.hasNode(historyNodeName)) {
            return null;
        }
        return n.getNode(historyNodeName, 1).getNodeId();
    }

    /**
     * Checks in a node
     *
     * @param node node to checkin
     * @return internal version
     * @throws javax.jcr.RepositoryException if an error occurs
     * @see javax.jcr.Node#checkin()
     */
    protected InternalVersion checkin(InternalVersionHistoryImpl history, NodeImpl node)
            throws RepositoryException {

        // 1. search a predecessor, suitable for generating the new name
        Value[] values = node.getProperty(QName.JCR_PREDECESSORS).getValues();
        InternalVersion best = null;
        for (int i = 0; i < values.length; i++) {
            InternalVersion pred = history.getVersion(NodeId.valueOf(values[i].getString()));
            if (best == null || pred.getSuccessors().length < best.getSuccessors().length) {
                best = pred;
            }
        }

        // 2. generate version name (assume no namespaces in version names)
        String versionName = best.getName().getLocalName();
        int pos = versionName.lastIndexOf('.');
        if (pos > 0) {
            versionName = versionName.substring(0, pos + 1)
                + (Integer.parseInt(versionName.substring(pos + 1)) + 1);
        } else {
            versionName = String.valueOf(best.getSuccessors().length + 1) + ".0";
        }

        // 3. check for colliding names
        while (history.hasVersion(new QName("", versionName))) {
            versionName += ".1";
        }

        try {
            stateMgr.edit();
        } catch (IllegalStateException e) {
            throw new RepositoryException("Unable to start edit operation.");
        }

        boolean succeeded = false;

        try {
            InternalVersionImpl v = history.checkin(new QName("", versionName), node);
            stateMgr.update();
            succeeded = true;

            return v;
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                stateMgr.cancel();
            }
        }
    }


    /**
     * Removes the specified version from the history
     *
     * @param history the version history from where to remove the version.
     * @param name the name of the version to remove.
     * @throws javax.jcr.version.VersionException if the version <code>history</code> does
     *  not have a version with <code>name</code>.
     * @throws javax.jcr.RepositoryException if any other error occurs.
     */
    protected void removeVersion(InternalVersionHistoryImpl history, QName name)
            throws VersionException, RepositoryException {

        try {
            stateMgr.edit();
        } catch (IllegalStateException e) {
            throw new VersionException("Unable to start edit operation", e);
        }
        boolean succeeded = false;
        try {
            history.removeVersion(name);
            stateMgr.update();
            succeeded = true;
        } catch (ItemStateException e) {
            log.error("Error while storing: " + e.toString());
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                stateMgr.cancel();
            }
        }
    }

    /**
     * Set version label on the specified version.
     * @param history version history
     * @param version version name
     * @param label version label
     * @param move <code>true</code> to move from existing version;
     *             <code>false</code> otherwise
     * @throws RepositoryException if an error occurs
     */
    protected InternalVersion setVersionLabel(InternalVersionHistoryImpl history,
                                              QName version, QName label,
                                              boolean move)
            throws RepositoryException {

        try {
            stateMgr.edit();
        } catch (IllegalStateException e) {
            throw new VersionException("Unable to start edit operation", e);
        }
        InternalVersion v = null;
        boolean success = false;
        try {
            v = history.setVersionLabel(version, label, move);
            stateMgr.update();
            success = true;
        } catch (ItemStateException e) {
            log.error("Error while storing: " + e.toString());
        } finally {
            if (!success) {
                // update operation failed, cancel all modifications
                stateMgr.cancel();
            }
        }
        return v;
    }

    /**
     * Invoked when a new internal item has been created.
     * @param version internal version item
     */
    protected void versionCreated(InternalVersion version) {
    }

    /**
     * Invoked when a new internal item has been destroyed.
     * @param version internal version item
     */
    protected void versionDestroyed(InternalVersion version) {
    }
}
