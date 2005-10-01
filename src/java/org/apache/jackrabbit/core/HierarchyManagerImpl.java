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
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.log4j.Logger;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * <code>HierarchyManagerImpl</code> ...
 */
public class HierarchyManagerImpl implements HierarchyManager {

    private static Logger log = Logger.getLogger(HierarchyManagerImpl.class);

    protected final NodeId rootNodeId;
    protected final ItemStateManager provider;
    // used for outputting user-friendly paths and names
    protected final NamespaceResolver nsResolver;

    public HierarchyManagerImpl(String rootNodeUUID,
                                ItemStateManager provider,
                                NamespaceResolver nsResolver) {
        rootNodeId = new NodeId(rootNodeUUID);
        this.provider = provider;
        this.nsResolver = nsResolver;
    }

    public NodeId getRootNodeId() {
        return rootNodeId;
    }

    public NamespaceResolver getNamespaceResolver() {
        return nsResolver;
    }

    //-------------------------------------------------< misc. helper methods >
    /**
     * Failsafe conversion of internal <code>Path</code> to JCR path for use in
     * error messages etc.
     *
     * @param path path to convert
     * @return JCR path
     */
    public String safeGetJCRPath(Path path) {
        try {
            return path.toJCRPath(nsResolver);
        } catch (NoPrefixDeclaredException npde) {
            log.error("failed to convert " + path.toString() + " to JCR path.");
            // return string representation of internal path as a fallback
            return path.toString();
        }
    }

    /**
     * Failsafe translation of internal <code>ItemId</code> to JCR path for use
     * in error messages etc.
     *
     * @param id id to translate
     * @return JCR path
     */
    public String safeGetJCRPath(ItemId id) {
        try {
            return safeGetJCRPath(getPath(id));
        } catch (RepositoryException re) {
            log.error(id + ": failed to determine path to");
            // return string representation if id as a fallback
            return id.toString();
        }
    }

    //---------------------------------------------------------< overridables >
    /**
     * Return an item state, given its item id.
     * <p/>
     * Low-level hook provided for specialized derived classes.
     *
     * @param id item id
     * @return item state
     * @throws NoSuchItemStateException if the item does not exist
     * @throws ItemStateException       if an error occurs
     * @see ZombieHierarchyManager#getItemState(ItemId)
     */
    protected ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {
        return provider.getItemState(id);
    }

    /**
     * Determines whether an item state for a given item id exists.
     * <p/>
     * Low-level hook provided for specialized derived classes.
     *
     * @param id item id
     * @return <code>true</code> if an item state exists, otherwise
     *         <code>false</code>
     * @see ZombieHierarchyManager#hasItemState(ItemId)
     */
    protected boolean hasItemState(ItemId id) {
        return provider.hasItemState(id);
    }

    /**
     * Returns the <code>parentUUID</code> of the given item.
     * <p/>
     * Low-level hook provided for specialized derived classes.
     *
     * @param state item state
     * @return <code>parentUUID</code> of the given item
     * @see ZombieHierarchyManager#getParentUUID(ItemState)
     */
    protected String getParentUUID(ItemState state) {
        return state.getParentUUID();
    }

    /**
     * Returns the <code>ChildNodeEntry</code> of <code>parent</code> with the
     * specified <code>uuid</code> or <code>null</code> if there's no such entry.
     * <p/>
     * Low-level hook provided for specialized derived classes.
     *
     * @param parent node state
     * @param uuid   uuid of child node entry
     * @return the <code>ChildNodeEntry</code> of <code>parent</code> with
     *         the specified <code>uuid</code> or <code>null</code> if there's
     *         no such entry.
     * @see ZombieHierarchyManager#getChildNodeEntry(NodeState, String)
     */
    protected NodeState.ChildNodeEntry getChildNodeEntry(NodeState parent,
                                                         String uuid) {
        return parent.getChildNodeEntry(uuid);
    }

    /**
     * Returns the <code>ChildNodeEntry</code> of <code>parent</code> with the
     * specified <code>name</code> and <code>index</code> or <code>null</code>
     * if there's no such entry.
     * <p/>
     * Low-level hook provided for specialized derived classes.
     *
     * @param parent node state
     * @param name   name of child node entry
     * @param index  index of child node entry
     * @return the <code>ChildNodeEntry</code> of <code>parent</code> with
     *         the specified <code>name</code> and <code>index</code> or
     *         <code>null</code> if there's no such entry.
     * @see ZombieHierarchyManager#getChildNodeEntry(NodeState, QName, int)
     */
    protected NodeState.ChildNodeEntry getChildNodeEntry(NodeState parent,
                                                         QName name,
                                                         int index) {
        return parent.getChildNodeEntry(name, index);
    }

    /**
     * Resolve a path into an item id. Recursively invoked method that may be
     * overridden by some subclass to either return cached responses or add
     * response to cache.
     *
     * @param path full path of item to resolve
     * @param id   intermediate item id
     * @param next next path element index to resolve
     * @return the id of the item denoted by <code>path</code>
     */
    protected ItemId resolvePath(Path path, ItemId id, int next)
            throws RepositoryException {

        try {
            return resolvePath(path, getItemState(id), next);
        } catch (NoSuchItemStateException e) {
            String msg = "failed to retrieve state of intermediary node";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        } catch (ItemStateException e) {
            String msg = "failed to retrieve state of intermediary node";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * Resolve a path into an item id. Recursively invoked method that may be
     * overridden by some subclass to either return cached responses or add
     * response to cache.
     *
     * @param path  full path of item to resolve
     * @param state intermediate state
     * @param next  next path element index to resolve
     * @return the id of the item denoted by <code>path</code>
     */
    protected ItemId resolvePath(Path path, ItemState state, int next)
            throws PathNotFoundException, ItemStateException {

        Path.PathElement[] elements = path.getElements();
        if (elements.length == next) {
            return state.getId();
        }
        Path.PathElement elem = elements[next];

        QName name = elem.getName();
        int index = elem.getIndex();
        if (index == 0) {
            index = 1;
        }

        NodeState parentState = (NodeState) state;
        ItemId childId;

        if (parentState.hasChildNodeEntry(name, index)) {
            // child node
            NodeState.ChildNodeEntry nodeEntry =
                    getChildNodeEntry(parentState, name, index);
            childId = new NodeId(nodeEntry.getUUID());

        } else if (parentState.hasPropertyName(name)) {
            // property
            if (index > 1) {
                // properties can't have same name siblings
                throw new PathNotFoundException(safeGetJCRPath(path));

            } else if (next < elements.length - 1) {
                // property is not the last element in the path
                throw new PathNotFoundException(safeGetJCRPath(path));
            }

            childId = new PropertyId(parentState.getUUID(), name);

        } else {
            // no such item
            throw new PathNotFoundException(safeGetJCRPath(path));
        }
        return resolvePath(path, getItemState(childId), next + 1);
    }

    /**
     * Adds the path element of an item id to the path currently being built.
     * Recursively invoked method that may be overridden by some subclass to
     * either return cached responses or add response to cache. On exit,
     * <code>builder</code> contains the path of <code>state</code>.
     *
     * @param builder builder currently being used
     * @param state   item to find path of
     */
    protected void buildPath(Path.PathBuilder builder, ItemState state)
            throws ItemStateException, RepositoryException {

        // shortcut
        if (state.getId().equals(rootNodeId)) {
            builder.addRoot();
            return;
        }

        String parentUUID = getParentUUID(state);
        if (parentUUID == null) {
            String msg = "failed to build path of " + state.getId()
                    + ": orphaned item";
            log.debug(msg);
            throw new ItemNotFoundException(msg);
        }

        NodeState parent = (NodeState) getItemState(new NodeId(parentUUID));
        // recursively build path of parent
        buildPath(builder, parent);

        if (state.isNode()) {
            NodeState nodeState = (NodeState) state;
            String uuid = nodeState.getUUID();
            NodeState.ChildNodeEntry entry = getChildNodeEntry(parent, uuid);
            if (entry == null) {
                String msg = "failed to build path of " + state.getId() + ": "
                        + parent.getUUID() + " has no child entry for "
                        + uuid;
                log.debug(msg);
                throw new ItemNotFoundException(msg);
            }
            // add to path
            if (entry.getIndex() == 1) {
                builder.addLast(entry.getName());
            } else {
                builder.addLast(entry.getName(), entry.getIndex());
            }
        } else {
            PropertyState propState = (PropertyState) state;
            QName name = propState.getName();
            // add to path
            builder.addLast(name);
        }
    }

    //-----------------------------------------------------< HierarchyManager >
    /**
     * {@inheritDoc}
     */
    public ItemId resolvePath(Path path)
            throws PathNotFoundException, RepositoryException {
        // shortcut
        if (path.denotesRoot()) {
            return rootNodeId;
        }

        if (!path.isCanonical()) {
            String msg = "path is not canonical";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        return resolvePath(path, rootNodeId, 1);
    }

    /**
     * {@inheritDoc}
     */
    public Path getPath(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        // shortcut
        if (id.equals(rootNodeId)) {
            return Path.ROOT;
        }

        Path.PathBuilder builder = new Path.PathBuilder();

        try {
            buildPath(builder, getItemState(id));
            return builder.getPath();
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to build path of " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to build path of " + id;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        } catch (MalformedPathException mpe) {
            String msg = "failed to build path of " + id;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
    }

    /**
     * {@inheritDoc}
     */
    public QName getName(ItemId itemId)
            throws ItemNotFoundException, RepositoryException {
        if (itemId.denotesNode()) {
            NodeId nodeId = (NodeId) itemId;
            NodeState parentState;
            try {
                NodeState nodeState = (NodeState) getItemState(nodeId);
                String parentUUID = getParentUUID(nodeState);
                if (parentUUID == null) {
                    // this is the root or an orphaned node
                    // FIXME
                    return QName.EMPTY;
                }
                parentState = (NodeState) getItemState(new NodeId(parentUUID));
            } catch (NoSuchItemStateException nsis) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new ItemNotFoundException(nodeId.toString());
            } catch (ItemStateException ise) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new RepositoryException(msg, ise);
            }

            NodeState.ChildNodeEntry entry =
                    getChildNodeEntry(parentState, nodeId.getUUID());
            if (entry == null) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new RepositoryException(msg);
            }
            return entry.getName();
        } else {
            PropertyId propId = (PropertyId) itemId;
            return propId.getName();
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getDepth(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        // shortcut
        if (id.equals(rootNodeId)) {
            return 0;
        }
        try {
            ItemState state = getItemState(id);
            String parentUUID = getParentUUID(state);
            int depth = 0;
            while (parentUUID != null) {
                depth++;
                state = getItemState(new NodeId(parentUUID));
                parentUUID = getParentUUID(state);
            }
            return depth;
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to determine depth of " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to determine depth of " + id;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getRelativeDepth(NodeId ancestorId, ItemId descendantId)
            throws ItemNotFoundException, RepositoryException {
        if (ancestorId.equals(descendantId)) {
            return 0;
        }
        int depth = 1;
        try {
            ItemState state = getItemState(descendantId);
            String parentUUID = getParentUUID(state);
            while (parentUUID != null) {
                if (parentUUID.equals(ancestorId.getUUID())) {
                    return depth;
                }
                depth++;
                state = getItemState(new NodeId(parentUUID));
                parentUUID = getParentUUID(state);
            }
            // not an ancestor
            return -1;
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to determine depth of " + descendantId
                    + " relative to " + ancestorId;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to determine depth of " + descendantId
                    + " relative to " + ancestorId;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isAncestor(NodeId nodeId, ItemId itemId)
            throws ItemNotFoundException, RepositoryException {
        if (nodeId.equals(itemId)) {
            // can't be ancestor of self
            return false;
        }
        try {
            ItemState state = getItemState(itemId);
            String parentUUID = getParentUUID(state);
            while (parentUUID != null) {
                if (parentUUID.equals(nodeId.getUUID())) {
                    return true;
                }
                state = getItemState(new NodeId(parentUUID));
                parentUUID = getParentUUID(state);
            }
            // not an ancestor
            return false;
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to determine degree of relationship of "
                    + nodeId + " and " + itemId;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to determine degree of relationship of "
                    + nodeId + " and " + itemId;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }
}

