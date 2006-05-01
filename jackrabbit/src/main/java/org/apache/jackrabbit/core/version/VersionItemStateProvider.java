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

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateListener;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.SharedItemStateManager;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.core.virtual.VirtualNodeState;
import org.apache.jackrabbit.core.virtual.VirtualPropertyState;
import org.apache.jackrabbit.name.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;

/**
 * This Class implements a virtual item state provider.
 */
class VersionItemStateProvider implements VirtualItemStateProvider, ItemStateListener {

    /**
     * the default logger
     */
    private static Logger log = LoggerFactory.getLogger(VersionItemStateProvider.class);

    /**
     * The version manager
     */
    private final VersionManagerImpl vMgr;

    /**
     * The root node UUID for the version storage
     */
    private final NodeId historyRootId;

    /**
     * The item state manager directly on the version persistence mgr
     */
    private final SharedItemStateManager stateMgr;

    /**
     * Map of returned items. this is kept for invalidating
     */
    private ReferenceMap items = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.WEAK);

    /**
     * Creates a bew vesuion manager
     *
     */
    public VersionItemStateProvider(VersionManagerImpl vMgr, SharedItemStateManager stateMgr) {
        this.vMgr = vMgr;
        this.stateMgr = stateMgr;
        this.historyRootId = vMgr.getHistoryRootId();
    }

    /**
     * @inheritDoc
     */
    public boolean isVirtualRoot(ItemId id) {
        return id.equals(historyRootId);
    }

    /**
     * @inheritDoc
     */
    public NodeId getVirtualRootId() {
        return historyRootId;
    }

    /**
     * @inheritDoc
     */
    public VirtualPropertyState createPropertyState(VirtualNodeState parent,
                                                    QName name, int type,
                                                    boolean multiValued)
            throws RepositoryException {
        throw new IllegalStateException("VersionManager should never create a VirtualPropertyState");
    }

    /**
     * @inheritDoc
     */
    public VirtualNodeState createNodeState(VirtualNodeState parent, QName name,
                                            NodeId id, QName nodeTypeName)
            throws RepositoryException {
        throw new IllegalStateException("VersionManager should never create a VirtualNodeState");
    }

    /**
     * @inheritDoc
     */
    public synchronized ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {
        ItemState item = (ItemState) items.get(id);
        if (item == null) {
            item = stateMgr.getItemState(id);
            items.put(id, item);

            // attach us as listener
            item.addListener(this);
        }
        return item;
    }

    /**
     * @inheritDoc
     */
    public boolean setNodeReferences(NodeReferences refs) {
        return vMgr.setNodeReferences(refs);
    }

    /**
     * @inheritDoc
     */
    public boolean hasItemState(ItemId id) {
        return items.get(id) != null || stateMgr.hasItemState(id);
    }

    /**
     * @inheritDoc
     */
    public NodeReferences getNodeReferences(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {
        return stateMgr.getNodeReferences(id);
    }

    /**
     * @inheritDoc
     */
    public boolean hasNodeReferences(NodeReferencesId id) {
        return stateMgr.hasNodeReferences(id);
    }

    /**
     * @inheritDoc
     */
    public void stateCreated(ItemState created) {
        // ignore
    }

    /**
     * @inheritDoc
     */
    public void stateModified(ItemState modified) {
        // ignore
    }

    /**
     * @inheritDoc
     */
    public void stateDestroyed(ItemState destroyed) {
        items.remove(destroyed.getId());
    }

    /**
     * @inheritDoc
     */
    public void stateDiscarded(ItemState discarded) {
        items.remove(discarded.getId());
    }
}
