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
import org.apache.log4j.Logger;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * <code>HierarchyManagerImpl</code> ...
 */
public class HierarchyManagerImpl implements HierarchyManager {

    private static Logger log = Logger.getLogger(HierarchyManagerImpl.class);

    private final NodeId rootNodeId;
    private final ItemStateManager provider;
    private final ItemStateManager attic;
    // used for outputting user-friendly paths and names
    private final NamespaceResolver nsResolver;

    public HierarchyManagerImpl(String rootNodeUUID,
                                ItemStateManager provider,
                                NamespaceResolver nsResolver) {
        this(rootNodeUUID, provider, nsResolver, null);
    }

    public HierarchyManagerImpl(String rootNodeUUID,
                                ItemStateManager provider,
                                NamespaceResolver nsResolver,
                                ItemStateManager attic) {
        this.rootNodeId = new NodeId(rootNodeUUID);
        this.provider = provider;
        this.attic = attic;
        this.nsResolver = nsResolver;
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

    //-----------------------------------------------------< HierarchyManager >
    /**
     * {@inheritDoc}
     */
    public NodeId[] listParents(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        ArrayList list = new ArrayList();
        try {
            if (id.denotesNode()) {
                NodeState state = (NodeState) getItemState(id);
                Iterator iter = state.getParentUUIDs().iterator();
                while (iter.hasNext()) {
                    list.add(new NodeId((String) iter.next()));
                }
            } else {
                PropertyState state = (PropertyState) getItemState(id);
                list.add(new NodeId(state.getParentUUID()));
            }
        } catch (NoSuchItemStateException e) {
            String msg = "failed to retrieve state of item " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg, e);
        } catch (ItemStateException e) {
            String msg = "failed to retrieve state of item " + id;
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
        return (NodeId[]) list.toArray(new NodeId[list.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public ItemId[] listChildren(NodeId id)
            throws ItemNotFoundException, RepositoryException {
        NodeState parentState;
        try {
            parentState = (NodeState) getItemState(id);
        } catch (NoSuchItemStateException e) {
            String msg = "failed to retrieve state of parent node " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg, e);
        } catch (ItemStateException e) {
            String msg = "failed to retrieve state of parent node " + id;
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
        ArrayList list = new ArrayList();
        Iterator iter = parentState.getPropertyEntries().iterator();
        while (iter.hasNext()) {
            // properties
            NodeState.PropertyEntry pe = (NodeState.PropertyEntry) iter.next();
            list.add(new PropertyId(id.getUUID(), pe.getName()));
        }
        iter = parentState.getChildNodeEntries().iterator();
        while (iter.hasNext()) {
            // child nodes
            NodeState.ChildNodeEntry cne = (NodeState.ChildNodeEntry) iter.next();
            list.add(new NodeId(cne.getUUID()));
        }
        return (ItemId[]) list.toArray(new ItemId[list.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public ItemId[] listZombieChildren(NodeId id)
            throws ItemNotFoundException, RepositoryException {
        // FIXME messy code
        NodeState parentState;
        try {
            parentState = (NodeState) getItemState(id, true);
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to retrieve state of parent node " + id;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to retrieve state of parent node " + id;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }

        ArrayList list = new ArrayList();
        Iterator iter = parentState.getRemovedPropertyEntries().iterator();
        while (iter.hasNext()) {
            // removed properties
            NodeState.PropertyEntry pe = (NodeState.PropertyEntry) iter.next();
            list.add(new PropertyId(id.getUUID(), pe.getName()));
        }
        iter = parentState.getRemovedChildNodeEntries().iterator();
        while (iter.hasNext()) {
            // removed child nodes
            NodeState.ChildNodeEntry cne = (NodeState.ChildNodeEntry) iter.next();
            list.add(new NodeId(cne.getUUID()));
        }
        return (ItemId[]) list.toArray(new ItemId[list.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized ItemId resolvePath(Path path)
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

        NodeState parentState;
        try {
            parentState = (NodeState) getItemState(rootNodeId);
        } catch (ItemStateException e) {
            String msg = "failed to retrieve state of root node";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        Path.PathElement[] elems = path.getElements();
        for (int i = 1; i < elems.length; i++) {
            Path.PathElement elem = elems[i];
            QName name = elem.getName();
            int index = elem.getIndex();
            if (index == 0) {
                index = 1;
            }
            if (parentState.hasChildNodeEntry(name, index)) {
                // child node
                NodeState.ChildNodeEntry nodeEntry =
                        parentState.getChildNodeEntry(name, index);
                if (i == elems.length - 1) {
                    // last element in the path
                    return new NodeId(nodeEntry.getUUID());
                }
                try {
                    parentState =
                            (NodeState) getItemState(new NodeId(nodeEntry.getUUID()));
                } catch (ItemStateException e) {
                    String msg = "failed to retrieve state of intermediary node";
                    log.debug(msg);
                    throw new RepositoryException(msg, e);
                }
                continue;
            } else if (parentState.hasPropertyEntry(name)) {
                // property
                if (index > 1) {
                    // properties can't have same name siblings
                    throw new PathNotFoundException(safeGetJCRPath(path));
                }
                if (i == elems.length - 1) {
                    return new PropertyId(parentState.getUUID(), name);
                } else {
                    // property is not the last element in the path
                    throw new PathNotFoundException(safeGetJCRPath(path));
                }
            } else {
                // no such item
                throw new PathNotFoundException(safeGetJCRPath(path));
            }
        }

        throw new PathNotFoundException(safeGetJCRPath(path));
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Path getPath(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        try {
            Path.PathBuilder builder = new Path.PathBuilder();

            ItemState state = getItemState(id);
            String parentUUID = state.getParentUUID();
            if (parentUUID == null) {
                // specified id denotes the root node
                builder.addRoot();
                return builder.getPath();
            }

            NodeState parent = (NodeState) getItemState(new NodeId(parentUUID));
            do {
                if (state.isNode()) {
                    NodeState nodeState = (NodeState) state;
                    String uuid = nodeState.getUUID();
                    List entries = parent.getChildNodeEntries(uuid);
                    if (entries.isEmpty()) {
                        String msg = "failed to build path of " + id + ": "
                                + parent.getUUID() + " has no child entry for "
                                + uuid;
                        log.debug(msg);
                        throw new RepositoryException(msg);
                    }
                    // if the parent has more than one child node entries pointing
                    // to the same child node, always use the first one
                    NodeState.ChildNodeEntry entry =
                            (NodeState.ChildNodeEntry) entries.get(0);
                    // add to path
                    if (entry.getIndex() == 1) {
                        builder.addFirst(entry.getName());
                    } else {
                        builder.addFirst(entry.getName(), entry.getIndex());
                    }
                } else {
                    PropertyState propState = (PropertyState) state;
                    QName name = propState.getName();
                    // add to path
                    builder.addFirst(name);
                }
                parentUUID = parent.getParentUUID();
                if (parentUUID != null) {
                    state = parent;
                    parent = (NodeState) getItemState(new NodeId(parentUUID));
                } else {
                    parent = null;
                    state = null;
                }
            } while (parent != null);

            // add root to path
            builder.addRoot();
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
            if (!provider.hasItemState(nodeId)) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new ItemNotFoundException(nodeId.toString());
            }
            try {
                NodeState nodeState = (NodeState) getItemState(nodeId);
                String parentUUID = nodeState.getParentUUID();
                if (parentUUID == null) {
                    // this is the root or an orphaned node
                    // FIXME
                    return new QName(Constants.NS_DEFAULT_URI, "");
                }
                parentState = (NodeState) getItemState(new NodeId(parentUUID));
            } catch (ItemStateException ise) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new RepositoryException(msg, ise);
            }

            List entries = parentState.getChildNodeEntries(nodeId.getUUID());
            if (entries.size() == 0) {
                String msg = "failed to resolve name of " + nodeId;
                log.debug(msg);
                throw new RepositoryException(msg);
            }
            NodeState.ChildNodeEntry entry =
                    (NodeState.ChildNodeEntry) entries.get(0);
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
        try {
            int depth = 0;
            ItemState state = getItemState(id, true);
            String parentUUID = state.getParentUUID();
            while (parentUUID != null) {
                state = getItemState(new NodeId(parentUUID), true);
                parentUUID = state.getParentUUID();
                depth++;
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
    public boolean isAncestor(NodeId nodeId, ItemId itemId)
            throws ItemNotFoundException, RepositoryException {
        try {
            ItemState state = getItemState(itemId, true);
            String parentUUID = state.getParentUUID();
            while (parentUUID != null) {
                if (parentUUID.equals(nodeId.getUUID())) {
                    return true;
                }
                state = getItemState(new NodeId(parentUUID), true);
                parentUUID = state.getParentUUID();
            }
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

    /**
     * {@inheritDoc}
     */
    public synchronized Path[] getAllPaths(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        return getAllPaths(id, false);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Path[] getAllPaths(ItemId id, boolean includeZombies)
            throws ItemNotFoundException, RepositoryException {
        Path.PathBuilder builder = new Path.PathBuilder();
        ArrayList list = new ArrayList();
        list.add(builder);

        NodeId nodeId;
        if (!id.denotesNode()) {
            try {
                PropertyState propState =
                        (PropertyState) getItemState(id, includeZombies);
                QName name = propState.getName();
                // add to path
                builder.addFirst(name);
                nodeId = new NodeId(propState.getParentUUID());
            } catch (NoSuchItemStateException nsise) {
                String msg = "failed to build path of " + id;
                log.debug(msg);
                throw new ItemNotFoundException(msg, nsise);
            } catch (ItemStateException ise) {
                String msg = "failed to build path of " + id;
                log.debug(msg);
                throw new RepositoryException(msg, ise);
            }
        } else {
            nodeId = (NodeId) id;
        }

        // recursively traverse parent nodes
        recursiveBuildPaths(nodeId, builder, list, includeZombies);

        Path[] paths = new Path[list.size()];
        int i = 0;
        Iterator iter = list.iterator();
        while (iter.hasNext()) {
            Path.PathBuilder pb = (Path.PathBuilder) iter.next();
            try {
                paths[i++] = pb.getPath();
            } catch (MalformedPathException mpe) {
                String msg = "failed to build all paths of " + id;
                log.debug(msg);
                throw new RepositoryException(msg, mpe);
            }
        }
        return paths;
    }

    //------------------------------------------------------< private methods >
    /**
     * @param id
     * @return
     * @throws NoSuchItemStateException
     * @throws ItemStateException
     */
    private ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        return provider.getItemState(id);
    }

    /**
     * @param id
     * @param includeZombies
     * @return
     * @throws NoSuchItemStateException
     * @throws ItemStateException
     */
    private ItemState getItemState(ItemId id, boolean includeZombies)
            throws NoSuchItemStateException, ItemStateException {
        if (includeZombies && attic != null && attic.hasItemState(id)) {
            // try attic first
            return attic.getItemState(id);
        }
        // fallback: get transient/persistent state
        return provider.getItemState(id);
    }

    /**
     * @param nodeId
     * @param builder
     * @param builders
     * @param includeZombies
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    private void recursiveBuildPaths(NodeId nodeId, Path.PathBuilder builder,
                                     List builders, boolean includeZombies)
            throws ItemNotFoundException, RepositoryException {
        try {
            NodeState nodeState = (NodeState) getItemState(nodeId, includeZombies);

            String uuid = nodeState.getUUID();
            /**
             * the parent uuid list contains an entry for every parent-child
             * link to the specified node. this list may contain duplicate
             * entries if the same parent node has more than one child link to
             * the same target node. because the following code expects unique
             * entries in the parent uuid list, we put them into a set.
             */
            HashSet parentUUIDs = new HashSet(nodeState.getParentUUIDs());
            if (includeZombies) {
                parentUUIDs.addAll(nodeState.getRemovedParentUUIDs());
            }
            if (parentUUIDs.size() == 0) {
                // nodeId denotes the root node
                builder.addRoot();
                return;
            }

            // if the specified node has n parents,
            // we need to create n - 1 path builder clones
            LinkedList queue = new LinkedList();
            queue.add(builder);
            int n = parentUUIDs.size() - 1;
            while (n-- > 0) {
                Path.PathBuilder clone = (Path.PathBuilder) builder.clone();
                queue.addLast(clone);
                // add to list of path builders
                builders.add(clone);
            }

            Iterator iter = parentUUIDs.iterator();
            while (iter.hasNext()) {
                String parentUUID = (String) iter.next();
                NodeState parent =
                        (NodeState) getItemState(new NodeId(parentUUID), includeZombies);
                ArrayList entries = new ArrayList(parent.getChildNodeEntries(uuid));
                if (includeZombies) {
                    Iterator riter = parent.getRemovedChildNodeEntries().iterator();
                    while (riter.hasNext()) {
                        NodeState.ChildNodeEntry entry =
                                (NodeState.ChildNodeEntry) riter.next();
                        if (entry.getUUID().equals(uuid)) {
                            entries.add(entry);
                        }
                    }
                }
                if (entries.isEmpty()) {
                    String msg = "failed to build path of " + nodeId
                            + ": " + parent.getUUID()
                            + " has no child entry for " + uuid;
                    log.debug(msg);
                    throw new ItemNotFoundException(msg);
                }
                n = entries.size() - 1;
                while (n-- > 0) {
                    // the same parent has multiple child references to
                    // this node, more path builder clones are needed

                    // since we are consuming the queue of path builders
                    // starting at the tail, we can safely clone 'builder'
                    // because it will be consumed as the very last queue entry
                    Path.PathBuilder clone = (Path.PathBuilder) builder.clone();
                    queue.add(clone);
                    // add to list of path builders
                    builders.add(clone);
                }
                for (int i = 0; i < entries.size(); i++) {
                    NodeState.ChildNodeEntry entry =
                            (NodeState.ChildNodeEntry) entries.get(i);

                    // get a path builder clone from the tail of the queue
                    Path.PathBuilder pb = (Path.PathBuilder) queue.removeLast();
                    // add entry to path
                    if (entry.getIndex() == 1) {
                        pb.addFirst(entry.getName());
                    } else {
                        pb.addFirst(entry.getName(), entry.getIndex());
                    }

                    // recurse
                    recursiveBuildPaths(new NodeId(parentUUID), pb, builders, includeZombies);
                }
            }
        } catch (NoSuchItemStateException nsise) {
            String msg = "failed to build path of " + nodeId;
            log.debug(msg);
            throw new ItemNotFoundException(msg, nsise);
        } catch (ItemStateException ise) {
            String msg = "failed to build path of " + nodeId;
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }
}

