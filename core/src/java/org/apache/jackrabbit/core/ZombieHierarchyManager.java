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

import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.log4j.Logger;

import java.util.Iterator;

/**
 * <code>HierarchyManager</code> implementation that is also able to
 * build/resolve paths of those items that have been moved or removed
 * (i.e. moved to the attic).
 * <p/>
 * todo make use of path caching
 */
public class ZombieHierarchyManager extends HierarchyManagerImpl {

    private static Logger log = Logger.getLogger(ZombieHierarchyManager.class);

    protected ItemStateManager attic;

    public ZombieHierarchyManager(String rootNodeUUID,
                                  ItemStateManager provider,
                                  ItemStateManager attic,
                                  NamespaceResolver nsResolver) {
        super(rootNodeUUID, provider, nsResolver);
        this.attic = attic;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Delivers state from attic if such exists, otherwise calls base class.
     */
    protected ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {
        // always check attic first
        if (attic.hasItemState(id)) {
            return attic.getItemState(id);
        }
        // delegate to base class
        return super.getItemState(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Returns <code>true</code>  if there's state on the attic for the
     * requested item; otherwise delegates to base class.
     */
    protected boolean hasItemState(ItemId id) {
        // always check attic first
        if (attic.hasItemState(id)) {
            return true;
        }
        // delegate to base class
        return super.hasItemState(id);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Also allows for removed items.
     */
    protected String getParentUUID(ItemState state) {
        if (state.hasOverlayedState()) {
            // use 'old' parent in case item has been removed
            return state.getOverlayedState().getParentUUID();
        }
        // delegate to base class
        return super.getParentUUID(state);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Also allows for removed/renamed child node entries.
     */
    protected NodeState.ChildNodeEntry getChildNodeEntry(NodeState parent,
                                                         QName name,
                                                         int index) {
        // check removed child node entries first
        Iterator iter = parent.getRemovedChildNodeEntries().iterator();
        while (iter.hasNext()) {
            NodeState.ChildNodeEntry entry =
                    (NodeState.ChildNodeEntry) iter.next();
            if (entry.getName().equals(name)
                    && entry.getIndex() == index) {
                return entry;
            }
        }
        // no matching removed child node entry found in parent,
        // delegate to base class
        return super.getChildNodeEntry(parent, name, index);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Also allows for removed child node entries.
     */
    protected NodeState.ChildNodeEntry getChildNodeEntry(NodeState parent,
                                                         String uuid) {
        // check removed child node entries first
        Iterator iter = parent.getRemovedChildNodeEntries().iterator();
        while (iter.hasNext()) {
            NodeState.ChildNodeEntry entry =
                    (NodeState.ChildNodeEntry) iter.next();
            if (entry.getUUID().equals(uuid)) {
                return entry;
            }
        }
        // no matching removed child node entry found in parent,
        // delegate to base class
        return super.getChildNodeEntry(parent, uuid);
    }
}
