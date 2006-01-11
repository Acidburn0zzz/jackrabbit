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

import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.version.InternalVersion;
import org.apache.jackrabbit.core.version.AbstractVersion;
import org.apache.jackrabbit.core.version.XAVersion;
import org.apache.jackrabbit.core.version.AbstractVersionHistory;
import org.apache.jackrabbit.core.version.InternalVersionHistory;
import org.apache.jackrabbit.core.version.XAVersionHistory;
import org.apache.jackrabbit.core.version.XAVersionManager;
import org.apache.log4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.nodetype.NodeDefinition;

/**
 * Extended <code>ItemManager</code> that works in an XA environment.
 */
public class XAItemManager extends ItemManager {

    /**
     * Logger instance.
     */
    private static Logger log = Logger.getLogger(XAItemManager.class);

    /**
     * Create a new instance of this class.
     * @param itemStateProvider the item state provider associated with
     *                          the new instance
     * @param session           the session associated with the new instance
     * @param rootNodeDef       the definition of the root node
     * @param rootNodeUUID      the UUID of the root node
     */
    protected XAItemManager(ItemStateManager itemStateProvider, HierarchyManager hierMgr,
                SessionImpl session, NodeDefinition rootNodeDef,
                String rootNodeUUID) {

        super(itemStateProvider, hierMgr, session, rootNodeDef, rootNodeUUID);
    }

    /**
     * {@inheritDoc}
     */
    protected AbstractVersion createVersionInstance(
            NodeId id, NodeState state, NodeDefinition def,
            ItemLifeCycleListener[] listeners) throws RepositoryException {

        InternalVersion version =
                session.getVersionManager().getVersion(id.getUUID());
        return new XAVersion(this, session, id, state, def, listeners, version);
    }

    /**
     * {@inheritDoc}
     */
    protected AbstractVersionHistory createVersionHistoryInstance(
            NodeId id, NodeState state, NodeDefinition def,
            ItemLifeCycleListener[] listeners) throws RepositoryException {

        InternalVersionHistory history =
                session.getVersionManager().getVersionHistory(id.getUUID());
        return new XAVersionHistory(this, session, id, state, def, listeners, history);
    }
}
