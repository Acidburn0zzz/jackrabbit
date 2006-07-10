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
package org.apache.jackrabbit.core;

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.NodeStateListener;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.util.PathMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Implementation of a <code>HierarchyManager</code> that caches paths of
 * items.
 */
public class CachingHierarchyManager extends HierarchyManagerImpl
        implements NodeStateListener {

    /**
     * Default upper limit of cached states
     */
    public static final int DEFAULT_UPPER_LIMIT = 10000;

    /**
     * Logger instance
     */
    private static Logger log = LoggerFactory.getLogger(CachingHierarchyManager.class);

    /**
     * Mapping of paths to children in the path map
     */
    private final PathMap pathCache = new PathMap();

    /**
     * Mapping of item ids to <code>LRUEntry</code> in the path map
     */
    private final ReferenceMap idCache = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.HARD);

    /**
     * Set of items that were moved
     */
    private final Set movedIds = new HashSet();

    /**
     * Cache monitor object
     */
    private final Object cacheMonitor = new Object();

    /**
     * Upper limit
     */
    private final int upperLimit;

    /**
     * Head of LRU
     */
    private LRUEntry head;

    /**
     * Tail of LRU
     */
    private LRUEntry tail;

    /**
     * Create a new instance of this class.
     *
     * @param rootNodeId   root node id
     * @param provider     item state manager
     * @param nsResolver   namespace resolver
     */
    public CachingHierarchyManager(NodeId rootNodeId,
                                   ItemStateManager provider,
                                   NamespaceResolver nsResolver) {
        super(rootNodeId, provider, nsResolver);
        upperLimit = DEFAULT_UPPER_LIMIT;
    }

    //-------------------------------------------------< base class overrides >
    /**
     * {@inheritDoc}
     * <p/>
     * Cache the intermediate item inside our cache.
     */
    protected ItemId resolvePath(Path path, ItemState state, int next)
            throws PathNotFoundException, ItemStateException {

        if (state.isNode() && !isCached(state.getId())) {
            try {
                Path.PathBuilder builder = new Path.PathBuilder();
                Path.PathElement[] elements = path.getElements();
                for (int i = 0; i < next; i++) {
                    builder.addLast(elements[i]);
                }
                Path parentPath = builder.getPath();
                cache((NodeState) state, parentPath);
            } catch (MalformedPathException mpe) {
                log.warn("Failed to build path of " + state.getId(), mpe);
            }
        }
        return super.resolvePath(path, state, next);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Overridden method tries to find a mapping for the intermediate item
     * <code>state</code> and add its path elements to the builder currently
     * being used. If no mapping is found, the item is cached instead after
     * the base implementation has been invoked.
     */
    protected void buildPath(Path.PathBuilder builder, ItemState state)
            throws ItemStateException, RepositoryException {

        if (state.isNode()) {
            PathMap.Element element = get(state.getId());
            if (element != null) {
                try {
                    Path.PathElement[] elements = element.getPath().getElements();
                    for (int i = elements.length - 1; i >= 0; i--) {
                        builder.addFirst(elements[i]);
                    }
                    return;
                } catch (MalformedPathException mpe) {
                    String msg = "Failed to build path of " + state.getId();
                    log.debug(msg);
                    throw new RepositoryException(msg, mpe);
                }
            }
        }

        super.buildPath(builder, state);

        if (state.isNode()) {
            try {
                cache((NodeState) state, builder.getPath());
            } catch (MalformedPathException mpe) {
                log.warn("Failed to build path of " + state.getId());
            }
        }
    }

    //-----------------------------------------------------< HierarchyManager >
    /**
     * {@inheritDoc}
     * <p/>
     * Check the path indicated inside our cache first.
     */
    public ItemId resolvePath(Path path)
            throws PathNotFoundException, RepositoryException {

        // Run base class shortcut and sanity checks first
        if (path.denotesRoot()) {
            return rootNodeId;
        } else if (!path.isCanonical()) {
            String msg = "path is not canonical";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        PathMap.Element element = map(path);
        if (element == null) {
            return super.resolvePath(path);
        }
        LRUEntry entry = (LRUEntry) element.get();
        if (element.hasPath(path)) {
            entry.touch();
            return entry.getId();
        }
        return super.resolvePath(path, entry.getId(), element.getDepth() + 1);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Overridden method simply checks whether we have an item matching the id
     * and returns its path, otherwise calls base implementation.
     */
    public Path getPath(ItemId id)
            throws ItemNotFoundException, RepositoryException {

        if (id.denotesNode()) {
            PathMap.Element element = get(id);
            if (element != null) {
                try {
                    return element.getPath();
                } catch (MalformedPathException mpe) {
                    String msg = "Failed to build path of " + id;
                    log.debug(msg);
                    throw new RepositoryException(msg, mpe);
                }
            }
        }
        return super.getPath(id);
    }

    /**
     * {@inheritDoc}
     */
    public QName getName(ItemId id)
            throws ItemNotFoundException, RepositoryException {

        if (id.denotesNode()) {
            PathMap.Element element = get(id);
            if (element != null) {
                return element.getName();
            }
        }
        return super.getName(id);
    }

    /**
     * {@inheritDoc}
     */
    public int getDepth(ItemId id)
            throws ItemNotFoundException, RepositoryException {

        if (id.denotesNode()) {
            PathMap.Element element = get(id);
            if (element != null) {
                return element.getDepth();
            }
        }
        return super.getDepth(id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isAncestor(NodeId nodeId, ItemId itemId)
            throws ItemNotFoundException, RepositoryException {

        if (itemId.denotesNode()) {
            PathMap.Element element = get(nodeId);
            if (element != null) {
                PathMap.Element child = get(itemId);
                if (child != null) {
                    return element.isAncestorOf(child);
                }
            }
        }
        return super.isAncestor(nodeId, itemId);
    }

    //----------------------------------------------------< ItemStateListener >

    /**
     * {@inheritDoc}
     */
    public void stateCreated(ItemState created) {
    }

    /**
     * {@inheritDoc}
     */
    public void stateModified(ItemState modified) {
        stateModified((NodeState) modified);
    }

    /**
     * Evict moved or renamed items from the cache.
     */
    private void stateModified(NodeState modified) {
        synchronized (cacheMonitor) {
            LRUEntry entry = (LRUEntry) idCache.get(modified.getNodeId());
            if (entry == null) {
                // Item not cached, ignore
                return;
            }

            PathMap.Element element = entry.getElement();

            Iterator iter = element.getChildren();
            while (iter.hasNext()) {
                PathMap.Element child = (PathMap.Element) iter.next();
                NodeState.ChildNodeEntry cne = modified.getChildNodeEntry(
                        child.getName(), child.getNormalizedIndex());
                if (cne == null) {
                    // Item does not exist, remove
                    child.remove();
                    remove(child);
                    return;
                }

                LRUEntry childEntry = (LRUEntry) child.get();
                if (childEntry != null && !cne.getId().equals(childEntry.getId())) {
                    // Different child item, remove
                    child.remove();
                    remove(child);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stateDestroyed(ItemState destroyed) {
        destroyed.removeListener(this);
        remove(destroyed.getId());
    }

    /**
     * {@inheritDoc}
     */
    public void stateDiscarded(ItemState discarded) {
        discarded.removeListener(this);
        if (discarded.isTransient() && !discarded.hasOverlayedState()) {
            // a new node has been discarded -> remove from cache
            remove(discarded.getId());
        } else if (provider.hasItemState(discarded.getId())) {
            evict(discarded.getId());
        } else {
            remove(discarded.getId());
        }
    }

    /**
     * Called when an <code>ItemState</code> has been overlaid by some
     * other state that now takes its identity. This notification is sent
     * on the state being overlaid.
     *
     * @param overlayer the <code>ItemState</code> that overlays this state
     */
    public void stateOverlaid(ItemState overlayer) {
        if (overlayer.isNode()) {
            overlayer.getOverlayedState().removeListener(this);
            overlayer.addListener(this);
        }
    }

    /**
     * Called when an <code>ItemState</code> no longer overlayes some other
     * item state. This notification is sent on the state overlaying another
     * state.
     *
     * @param overlayer the <code>ItemState</code> that overlaid another
     *                  item state. To get the overlaid state, invoke
     *                  {@link ItemState#getOverlayedState()}
     */
    public void stateUncovered(ItemState overlayer) {
        if (overlayer.isNode()) {
            overlayer.removeListener(this);
            overlayer.getOverlayedState().addListener(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void nodeAdded(NodeState state, QName name, int index, NodeId id) {
        try {
            Path path = Path.create(getPath(state.getNodeId()), name, index, true);
            insert(path, id);
        } catch (PathNotFoundException e) {
            log.warn("Unable to get path of node " + state.getNodeId()
                    + ", event ignored.");
        } catch (MalformedPathException e) {
            log.warn("Unable to create path of " + id, e);
        } catch (ItemNotFoundException e) {
            log.warn("Unable to get path of " + state.getNodeId(), e);
        } catch (RepositoryException e) {
            log.warn("Unable to get path of " + state.getNodeId(), e);
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Generate subsequent add and remove notifications for every replacement.
     */
    public void nodesReplaced(NodeState state) {
        List entries = state.getReorderedChildNodeEntries();
        if (entries.size() == 0) {
            return;
        }

        Iterator iter = entries.iterator();
        while (iter.hasNext()) {
            NodeState.ChildNodeEntry now = (NodeState.ChildNodeEntry) iter.next();
            NodeState.ChildNodeEntry old =
                    ((NodeState) state.getOverlayedState()).getChildNodeEntry(now.getId());

            if (old == null) {
                log.warn("Reordered child node not found in old list.");
                continue;
            }

            nodeAdded(state, now.getName(), now.getIndex(), now.getId());
            nodeRemoved(state, old.getName(), old.getIndex(), old.getId());
        }
    }

    /**
     * {@inheritDoc}
     */
    public void nodeRemoved(NodeState state, QName name, int index, NodeId id) {
        try {
            Path path = Path.create(getPath(state.getNodeId()), name, index, true);
            remove(path, id);
        } catch (PathNotFoundException e) {
            log.warn("Unable to get path of node " + state.getNodeId()
                    + ", event ignored.");
        } catch (MalformedPathException e) {
            log.warn("Unable to create path of " + id, e);
        } catch (ItemNotFoundException e) {
            log.warn("Unable to get path of " + state.getNodeId(), e);
        } catch (RepositoryException e) {
            log.warn("Unable to get path of " + state.getNodeId(), e);
        }
    }

    //------------------------------------------------------< private methods >

    /**
     * Return a cached element in the path map, given its id
     *
     * @param id node id
     * @return cached element, <code>null</code> if not found
     */
    private PathMap.Element get(ItemId id) {
        synchronized (cacheMonitor) {
            LRUEntry entry = (LRUEntry) idCache.get(id);
            if (entry != null) {
                entry.touch();
                return entry.getElement();
            }
            return null;
        }
    }

    /**
     * Return the nearest cached element in the path map, given a path.
     * The returned element is guaranteed to have an associated object that
     * is not <code>null</code>.
     *
     * @param path path
     * @return cached element, <code>null</code> if not found
     */
    private PathMap.Element map(Path path) {
        synchronized (cacheMonitor) {
            PathMap.Element element = pathCache.map(path, false);
            while (element != null) {
                LRUEntry entry = (LRUEntry) element.get();
                if (entry != null) {
                    entry.touch();
                    return element;
                }
                element = element.getParent();
            }
            return null;
        }
    }

    /**
     * Cache an item in the hierarchy given its id and path. Adds a listener
     * for this item state to get notified about changes.
     *
     * @param state node state
     * @param path  path to item
     */
    private void cache(NodeState state, Path path) {
        NodeId id = state.getNodeId();

        synchronized (cacheMonitor) {
            if (idCache.get(id) != null) {
                return;
            }
            if (idCache.size() >= upperLimit) {
                removeLRU();
            }

            PathMap.Element element = pathCache.put(path);
            if (element.get() != null) {
                if (!id.equals(((LRUEntry) element.get()).getId())) {
                    log.warn("overwriting PathMap.Element");
                }
            }
            LRUEntry entry = new LRUEntry(id, element);
            element.set(entry);
            idCache.put(id, entry);

            state.addListener(this);
        }
    }

    /**
     * Remove least recently used item. Scans the LRU list from head to tail
     * and removes the first item that has no children.
     */
    private void removeLRU() {
        synchronized (cacheMonitor) {
            LRUEntry entry = head;
            while (entry != null) {
                PathMap.Element element = entry.getElement();
                if (element.getChildrenCount() == 0) {
                    evict(entry, true);
                    return;
                }
                entry = entry.getNext();
            }
        }
    }

    /**
     * Return a flag indicating whether a certain element is cached.
     *
     * @param id item id
     * @return <code>true</code> if the item is already cached;
     *         <code>false</code> otherwise
     */
    private boolean isCached(ItemId id) {
        synchronized (cacheMonitor) {
            return idCache.get(id) != null;
        }
    }

    /**
     * Remove item from cache. Removes the associated <code>LRUEntry</code>
     * and the <code>PathMap.Element</code> with it. Indexes of same name
     * sibling elements are shifted!
     *
     * @param id item id
     */
    private void remove(ItemId id) {
        synchronized (cacheMonitor) {
            LRUEntry entry = (LRUEntry) idCache.get(id);
            if (entry != null) {
                remove(entry, true);
            }
        }
    }

    /**
     * Remove item from cache. Index of same name sibling items are shifted!
     *
     * @param entry               LRU entry
     * @param removeFromPathCache whether to remove from path cache
     */
    private void remove(LRUEntry entry, boolean removeFromPathCache) {
        synchronized (cacheMonitor) {
            if (removeFromPathCache) {
                PathMap.Element element = entry.getElement();
                remove(element);
                element.remove();
            } else {
                idCache.remove(entry.getId());
                entry.remove();
            }
        }
    }

    /**
     * Evict item from cache. Index of same name sibling items are <b>not</b>
     * shifted!
     *
     * @param entry               LRU entry
     * @param removeFromPathCache whether to remove from path cache
     */
    private void evict(LRUEntry entry, boolean removeFromPathCache) {
        synchronized (cacheMonitor) {
            if (removeFromPathCache) {
                PathMap.Element element = entry.getElement();
                element.traverse(new PathMap.ElementVisitor() {
                    public void elementVisited(PathMap.Element element) {
                        evict((LRUEntry) element.get(), false);
                    }
                }, false);
                element.remove(false);
            } else {
                idCache.remove(entry.getId());
                entry.remove();
            }
        }
    }

    /**
     * Evict item from cache. Evicts the associated <code>LRUEntry</code>
     * and the <code>PathMap.Element</code> with it. Indexes of same name
     * sibling elements are <b>not</b> shifted!
     *
     * @param id item id
     */
    private void evict(ItemId id) {
        synchronized (cacheMonitor) {
            LRUEntry entry = (LRUEntry) idCache.get(id);
            if (entry != null) {
                evict(entry, true);
            }
        }
    }

    /**
     * Remove path map element from cache. This will traverse all children
     * of this element and remove the objects associated with them.
     * Index of same name sibling items are shifted!
     *
     * @param element path map element
     */
    private void remove(PathMap.Element element) {
        synchronized (cacheMonitor) {
            element.traverse(new PathMap.ElementVisitor() {
                public void elementVisited(PathMap.Element element) {
                    remove((LRUEntry) element.get(), false);
                }
            }, false);
        }
    }

    /**
     * Insert a node into the cache. This will automatically shift
     * all indexes of sibling nodes having index greater or equal.
     *
     * @param path child path
     * @param id   node id
     */
    private void insert(Path path, ItemId id) throws PathNotFoundException {
        synchronized (cacheMonitor) {
            PathMap.Element element = null;

            LRUEntry entry = (LRUEntry) idCache.get(id);
            if (entry != null) {
                element = entry.getElement();
                element.remove();
            }

            PathMap.Element parent = pathCache.map(path.getAncestor(1), true);
            if (parent != null) {
                parent.insert(path.getNameElement());
            }
            if (element != null) {
                pathCache.put(path, element);

                /* Remember this as a move */
                movedIds.add(id);
            }
        }
    }

    /**
     * Remove an item from the cache in order to shift the indexes
     * of items following this item.
     *
     * @param path child path
     * @param id   node id
     */
    private void remove(Path path, ItemId id) throws PathNotFoundException {
        synchronized (cacheMonitor) {
            /* If we remembered this as a move, ignore this event */
            if (movedIds.remove(id)) {
                return;
            }
            PathMap.Element parent = pathCache.map(path.getAncestor(1), true);
            if (parent != null) {
                PathMap.Element element = parent.remove(path.getNameElement());
                if (element != null) {
                    remove(element);
                }
            }
        }
    }

    /**
     * Entry in the LRU list
     */
    private class LRUEntry {

        /**
         * Previous entry
         */
        private LRUEntry previous;

        /**
         * Next entry
         */
        private LRUEntry next;

        /**
         * Node id
         */
        private final NodeId id;

        /**
         * Element in path map
         */
        private final PathMap.Element element;

        /**
         * Create a new instance of this class
         *
         * @param id node id
         */
        public LRUEntry(NodeId id, PathMap.Element element) {
            this.id = id;
            this.element = element;

            append();
        }

        /**
         * Append entry to end of LRU list
         */
        public void append() {
            if (tail == null) {
                head = this;
                tail = this;
            } else {
                previous = tail;
                tail.next = this;
                tail = this;
            }
        }

        /**
         * Remove entry from LRU list
         */
        public void remove() {
            if (previous != null) {
                previous.next = next;
            }
            if (next != null) {
                next.previous = previous;
            }
            if (head == this) {
                head = next;
            }
            if (tail == this) {
                tail = previous;
            }
            previous = null;
            next = null;
        }

        /**
         * Touch entry. Removes it from its current position in the LRU list
         * and moves it to the end.
         */
        public void touch() {
            remove();
            append();
        }

        /**
         * Return previous LRU entry
         *
         * @return previous LRU entry
         */
        public LRUEntry getPrevious() {
            return previous;
        }

        /**
         * Return next LRU entry
         *
         * @return next LRU entry
         */
        public LRUEntry getNext() {
            return next;
        }

        /**
         * Return node ID
         *
         * @return node ID
         */
        public NodeId getId() {
            return id;
        }

        /**
         * Return element in path map
         *
         * @return element in path map
         */
        public PathMap.Element getElement() {
            return element;
        }
    }
}
