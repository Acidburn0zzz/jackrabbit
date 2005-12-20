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
package org.apache.jackrabbit.core.query;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.state.ItemStateManager;

/**
 * Acts as an argument for the {@link QueryHandler} to keep the interface
 * stable. This class provides access to the environment where the query
 * handler is running in.
 */
public class QueryHandlerContext {

    /**
     * A <code>FileSystem</code> to store the search index
     */
    private final FileSystem fs;

    /**
     * The persistent <code>ItemStateManager</code>
     */
    private final ItemStateManager stateMgr;

    /**
     * The node type registry of the repository
     */
    private final NodeTypeRegistry ntRegistry;

    /**
     * The UUID of the root node.
     */
    private String rootUUID;

    /**
     * PropertyType registry to look up the type of a property with a given name.
     */
    private final PropertyTypeRegistry propRegistry;

    /**
     * The query handler for the jcr:system tree
     */
    private final QueryHandler parentHandler;

    /**
     * UUID of the node that should be excluded from indexing.
     */
    private final String excludedNodeUUID;

    /**
     * Creates a new context instance.
     *
     * @param fs               a {@link FileSystem} this <code>QueryHandler</code>
     *                         may use to store its index. If no
     *                         <code>FileSystem</code> has been configured
     *                         <code>fs</code> is <code>null</code>.
     * @param stateMgr         provides persistent item states.
     * @param rootUUID         the uuid of the root node.
     * @param ntRegistry       the node type registry.
     * @param parentHandler    the parent query handler or <code>null</code> it
     *                         there is no parent handler.
     * @param excludedNodeUUID uuid of the node that should be excluded from
     *                         indexing. Any descendant of that node is also
     *                         excluded from indexing.
     */
    public QueryHandlerContext(FileSystem fs,
                               ItemStateManager stateMgr,
                               String rootUUID,
                               NodeTypeRegistry ntRegistry,
                               QueryHandler parentHandler,
                               String excludedNodeUUID) {
        this.fs = fs;
        this.stateMgr = stateMgr;
        this.rootUUID = rootUUID;
        this.ntRegistry = ntRegistry;
        propRegistry = new PropertyTypeRegistry(ntRegistry);
        this.parentHandler = parentHandler;
        this.excludedNodeUUID = excludedNodeUUID;
        ntRegistry.addListener(propRegistry);
    }

    /**
     * Returns the persistent {@link ItemStateManager}
     * of the workspace this <code>QueryHandler</code> is based on.
     *
     * @return the persistent <code>ItemStateManager</code> of the current
     *         workspace.
     */
    public ItemStateManager getItemStateManager() {
        return stateMgr;
    }

    /**
     * Returns the {@link FileSystem} instance this <code>QueryHandler</code>
     * may use to store its index. If no <code>FileSystem</code> has been
     * configured this method returns <code>null</code>.
     *
     * @return the <code>FileSystem</code> instance for this
     *         <code>QueryHandler</code>.
     */
    public FileSystem getFileSystem() {
        return fs;
    }

    /**
     * Returns the UUID of the root node.
     * @return the UUID of the root node.
     */
    public String getRootUUID() {
        return rootUUID;
    }

    /**
     * Returns the PropertyTypeRegistry for this repository.
     * @return the PropertyTypeRegistry for this repository.
     */
    public PropertyTypeRegistry getPropertyTypeRegistry() {
        return propRegistry;
    }

    /**
     * Returns the NodeTypeRegistry for this repository.
     * @return the NodeTypeRegistry for this repository.
     */
    public NodeTypeRegistry getNodeTypeRegistry() {
        return ntRegistry;
    }

    /**
     * Returns the parent query handler.
     * @return the parent query handler.
     */
    public QueryHandler getParentHandler() {
        return parentHandler;
    }

    /**
     * Returns the uuid of the node that should be excluded from indexing. Any
     * descendant of this node is also excluded from indexing.
     *
     * @return the uuid of the exluded node.
     */
    public String getExcludedNodeUUID() {
        return excludedNodeUUID;
    }

    /**
     * Destroys this context and releases resources.
     */
    public void destroy() {
        ntRegistry.removeListener(propRegistry);
    }
}
