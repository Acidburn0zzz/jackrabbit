/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jackrabbit.jcr2spi.NodeImpl;
import org.apache.jackrabbit.jcr2spi.ItemManager;
import org.apache.jackrabbit.jcr2spi.SessionImpl;
import org.apache.jackrabbit.jcr2spi.ItemLifeCycleListener;
import org.apache.jackrabbit.jcr2spi.LazyItemIterator;
import org.apache.jackrabbit.jcr2spi.operation.AddLabel;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.jcr2spi.operation.RemoveLabel;
import org.apache.jackrabbit.jcr2spi.operation.Remove;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.ItemStateException;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameException;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.spi.PropertyId;

import javax.jcr.version.VersionHistory;
import javax.jcr.version.Version;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionException;
import javax.jcr.RepositoryException;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.AccessDeniedException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.nodetype.NodeDefinition;
import java.util.Set;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * <code>VersionHistoryImpl</code>...
 */
public class VersionHistoryImpl extends NodeImpl implements VersionHistory {

    private static Logger log = LoggerFactory.getLogger(VersionHistoryImpl.class);

    private final NodeState vhState;
    private final NodeState labelNodeState;

    public VersionHistoryImpl(ItemManager itemMgr, SessionImpl session,
                              NodeState state, NodeDefinition definition,
                              ItemLifeCycleListener[] listeners) throws VersionException {
        super(itemMgr, session, state, definition, listeners);

        // retrieve nodestate of the jcr:versionLabels node
        vhState = state;
        if (vhState.hasChildNodeEntry(QName.JCR_VERSIONLABELS)) {
            NodeState.ChildNodeEntry lnEntry = vhState.getChildNodeEntry(QName.JCR_VERSIONLABELS, Path.INDEX_DEFAULT);
            try {
                labelNodeState = (NodeState) itemStateMgr.getItemState(lnEntry.getId());
            } catch (ItemStateException e) {
                throw new VersionException("nt:versionHistory requires a mandatory, autocreated child node jcr:versionLabels.");
            }
        } else {
            throw new VersionException("nt:versionHistory requires a mandatory, autocreated child node jcr:versionLabels.");
        }
    }

    //-----------------------------------------------------< VersionHistory >---
    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionableUUID()
     */
    public String getVersionableUUID() throws RepositoryException {
        return getProperty(QName.JCR_VERSIONABLEUUID).getString();
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getRootVersion()
     */
    public Version getRootVersion() throws RepositoryException {
        NodeId vId = vhState.getChildNodeEntry(QName.JCR_ROOTVERSION, Path.INDEX_DEFAULT).getId();
        return (Version) itemMgr.getItem(vId);
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getAllVersions()
     */
    public VersionIterator getAllVersions() throws RepositoryException {
        Iterator childIter = vhState.getChildNodeEntries().iterator();
        Set versionIds = new HashSet();

        // all child-nodes except from jcr:versionLabels point to Versions.
        while (childIter.hasNext()) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) childIter.next();
            if (!QName.JCR_VERSIONLABELS.equals(entry.getName())) {
                versionIds.add(entry.getId());
            }
        }
        return new LazyItemIterator(itemMgr, versionIds);
    }

    /**
     *
     * @param versionName
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#getVersion(String)
     */
    public Version getVersion(String versionName) throws VersionException, RepositoryException {
        NodeId vId = getVersionId(versionName);
        return (Version) itemMgr.getItem(vId);
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionByLabel(String)
     */
    public Version getVersionByLabel(String label) throws RepositoryException {
        NodeId vId = getVersionIdByLabel(label);
        return (Version) itemMgr.getItem(vId);
    }

    /**
     *
     * @param versionName
     * @param label
     * @param moveLabel
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#addVersionLabel(String, String, boolean)
     */
    public void addVersionLabel(String versionName, String label, boolean moveLabel) throws VersionException, RepositoryException {
        QName qLabel = getQLabel(label);
        // TODO: ev. delegate to versionmanager
        Operation op = AddLabel.create(vhState.getNodeId(), getVersionId(versionName), qLabel, moveLabel);
        itemStateMgr.execute(op);
    }

    /**
     *
     * @param label
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#removeVersionLabel(String)
     */
    public void removeVersionLabel(String label) throws VersionException, RepositoryException {
        QName qLabel = getQLabel(label);
        // TODO: ev. delegate to versionmanager
        Operation op = RemoveLabel.create(vhState.getNodeId(), getVersionIdByLabel(label), qLabel);
        itemStateMgr.execute(op);
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     * @see VersionHistory#hasVersionLabel(String)
     */
    public boolean hasVersionLabel(String label) throws RepositoryException {
        QName l = getQLabel(label);
        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            if (qLabels[i].equals(l)) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @param version
     * @param string
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#hasVersionLabel(Version, String)
     */
    public boolean hasVersionLabel(Version version, String label) throws VersionException, RepositoryException {
        checkValidVersion(version);
        String vUUID = version.getUUID();
        QName l = getQLabel(label);

        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            if (qLabels[i].equals(l)) {
                NodeId vId = getVersionIdByLabel(qLabels[i]);
                return vUUID.equals(vId.getUUID());
            }
        }
        return false;
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionLabels()
     */
    public String[] getVersionLabels() throws RepositoryException {
        QName[] qLabels = getQLabels();
        String[] labels = new String[qLabels.length];

        for (int i = 0; i < qLabels.length; i++) {
            try {
                labels[i] = session.getNamespaceResolver().getJCRName(qLabels[i]);
            } catch (NoPrefixDeclaredException e) {
                // unexpected error. should not occur.
                throw new RepositoryException(e);
            }
        }
        return labels;
    }

    /**
     *
     * @param version
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#getVersionLabels(Version)
     */
    public String[] getVersionLabels(Version version) throws VersionException, RepositoryException {
        checkValidVersion(version);
        String vUUID = version.getUUID();

        List vlabels = new ArrayList();
        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            NodeId vId = getVersionIdByLabel(qLabels[i]);
            if (vUUID.equals(vId.getUUID())) {
                try {
                    vlabels.add(session.getNamespaceResolver().getJCRName(qLabels[i]));
                } catch (NoPrefixDeclaredException e) {
                    // should never occur
                    throw new RepositoryException("Unexpected error while accessing version label", e);
                }
            }
        }
        return (String[]) vlabels.toArray(new String[vlabels.size()]);
    }

    /**
     *
     * @param versionName
     * @throws ReferentialIntegrityException
     * @throws AccessDeniedException
     * @throws UnsupportedRepositoryOperationException
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#removeVersion(String)
     */
    public void removeVersion(String versionName) throws ReferentialIntegrityException, AccessDeniedException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
        try {
            NodeId vId = getVersionId(versionName);
            NodeState vState = (NodeState) itemStateMgr.getItemState(vId);

            // TODO: ev. delegate to versionmanager
            Operation rm = Remove.create(vState);
            itemStateMgr.execute(rm);
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    //---------------------------------------------------------------< Item >---
    /**
     *
     * @param otherItem
     * @return
     * @see Item#isSame(Item)
     */
    public boolean isSame(Item otherItem) {
        if (otherItem instanceof VersionHistoryImpl) {
            // since all version histories are referenceable, protected and live
            // in the same workspace, a simple comparison of the ids is sufficient
            VersionHistoryImpl other = ((VersionHistoryImpl) otherItem);
            return other.getId().equals(getId());
        }
        return false;
    }

    //------------------------------------------------------------< private >---
    /**
     *
     * @return
     */
    private QName[] getQLabels() {
        Set labelQNames = labelNodeState.getPropertyNames();
        return (QName[]) labelQNames.toArray(new QName[labelQNames.size()]);
    }

    /**
     *
     * @param versionName
     * @return
     * @throws VersionException
     * @throws RepositoryException
     */
    private NodeId getVersionId(String versionName) throws VersionException, RepositoryException {
        try {
            QName vQName = session.getNamespaceResolver().getQName(versionName);
            NodeState.ChildNodeEntry vEntry = vhState.getChildNodeEntry(vQName, Path.INDEX_DEFAULT);
            if (vEntry == null) {
                throw new VersionException("Version '" + versionName + "' does not exist in this version history.");
            } else {
                return vEntry.getId();
            }
        } catch (NameException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     *
     * @param label
     * @return
     * @throws VersionException
     * @throws RepositoryException
     */
    private NodeId getVersionIdByLabel(String label) throws VersionException, RepositoryException {
        QName qLabel = getQLabel(label);
        return getVersionIdByLabel(qLabel);
    }

    /**
     *
     * @param qLabel
     * @return
     * @throws VersionException
     * @throws RepositoryException
     */
    private NodeId getVersionIdByLabel(QName qLabel) throws VersionException, RepositoryException {
        if (labelNodeState.hasPropertyName(qLabel)) {
            // retrieve reference property value -> and convert it to a NodeId
            PropertyId pId = labelNodeState.getPropertyId(qLabel);
            Node version = ((Property) itemMgr.getItem(pId)).getNode();
            return (NodeId) session.getHierarchyManager().getItemId(version);
        } else {
            throw new VersionException("Version with label '" + qLabel + "' does not exist.");
        }
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     */
    private QName getQLabel(String label) throws RepositoryException {
        try {
            return session.getNamespaceResolver().getQName(label);
        } catch (NameException e) {
            String error = "Invalid version label: " + e.getMessage();
            log.error(error);
            throw new RepositoryException(error, e);
        }
    }

    /**
     * Checks if the specified version belongs to this <code>VersionHistory</code>.
     * This method throws <code>VersionException</code> if {@link Version#getContainingHistory()}
     * is not the same item than this <code>VersionHistory</code>.
     *
     * @param version
     * @throws javax.jcr.version.VersionException
     * @throws javax.jcr.RepositoryException
     */
    private void checkValidVersion(Version version) throws VersionException, RepositoryException {
        if (!version.getContainingHistory().isSame(this)) {
            throw new VersionException("Specified version '" + version.getName() + "' is not part of this history.");
        }
    }
}