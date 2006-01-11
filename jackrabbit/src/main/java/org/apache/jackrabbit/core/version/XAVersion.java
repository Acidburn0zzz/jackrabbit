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

import org.apache.jackrabbit.core.ItemLifeCycleListener;
import org.apache.jackrabbit.core.ItemManager;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.state.NodeState;

import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.RepositoryException;
import javax.jcr.InvalidItemStateException;

/**
 * Implementation of a {@link javax.jcr.version.Version} that works in an
 * XA environment.
 */
public class XAVersion extends AbstractVersion {

    /**
     * Internal version. Gets fetched again from the version manager if
     * needed.
     */
    private InternalVersion version;

    /**
     * XA Version manager.
     */
    private final XAVersionManager vMgr;

    /**
     * Create a new instance of this class.
     * @param itemMgr item manager
     * @param session session
     * @param id node id
     * @param state node state
     * @param definition node definition
     * @param listeners life cycle listeners
     */
    public XAVersion(ItemManager itemMgr, SessionImpl session, NodeId id,
                     NodeState state, NodeDefinition definition,
                     ItemLifeCycleListener[] listeners,
                     InternalVersion version) {
        super(itemMgr, session, id, state, definition, listeners);

        this.version = version;
        this.vMgr = (XAVersionManager) session.getVersionManager();
    }

    /**
     * {@inheritDoc}
     */
    protected InternalVersion getInternalVersion() throws RepositoryException {
        ensureUpToDate();
        sanityCheck();
        return version;
    }

    /**
     * {@inheritDoc}
     */
    protected void sanityCheck() throws RepositoryException {
        super.sanityCheck();

        if (version == null) {
            throw new InvalidItemStateException(id + ": the item does not exist anymore");
        }
    }

    /**
     * Ensure the internal version is up-to-date.
     */
    private synchronized void ensureUpToDate() throws RepositoryException {
        if (version != null) {
            if (vMgr.differentXAEnv((InternalVersionImpl) version)) {
                version = vMgr.getVersion(version.getId());
            }
        }
    }
}
