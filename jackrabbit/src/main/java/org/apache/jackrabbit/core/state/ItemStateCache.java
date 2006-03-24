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
package org.apache.jackrabbit.core.state;

import org.apache.jackrabbit.core.ItemId;

import java.util.Set;
import java.util.Collection;

/**
 * An <code>ItemStateCache</code> maintains a cache of <code>ItemState</code>
 * instances.
 */
public interface ItemStateCache {
    /**
     * Returns <code>true</code> if this cache contains an <code>ItemState</code>
     * object with the specified <code>id</code>.
     *
     * @param id id of <code>ItemState</code> object whose presence should be
     *           tested.
     * @return <code>true</code> if there's a corresponding cache entry,
     *         otherwise <code>false</code>.
     */
    boolean isCached(ItemId id);

    /**
     * Returns the <code>ItemState</code> object with the specified
     * <code>id</code> if it is present or <code>null</code> if no entry exists
     * with that <code>id</code>.
     *
     * @param id the id of the <code>ItemState</code> object to be returned.
     * @return the <code>ItemState</code> object with the specified
     *         <code>id</code> or or <code>null</code> if no entry exists
     *         with that <code>id</code>
     */
    ItemState retrieve(ItemId id);

    /**
     * Stores the specified <code>ItemState</code> object in the map
     * using its <code>ItemId</code> as the key.
     *
     * @param state the <code>ItemState</code> object to cache
     */
    void cache(ItemState state);

    /**
     * Removes the <code>ItemState</code> object with the specified id from
     * this cache if it is present.
     *
     * @param id the id of the <code>ItemState</code> object which should be
     *           removed from this cache.
     */
    void evict(ItemId id);

    /**
     * Clears all entries from this cache.
     */
    void evictAll();

    /**
     * Returns <code>true</code> if this cache contains no entries.
     *
     * @return <code>true</code> if this cache contains no entries.
     */
    boolean isEmpty();

    /**
     * Returns the number of entries in this cache.
     *
     * @return number of entries in this cache.
     */
    int size();

    /**
     * Returns an unmodifiable set view of the keys (i.e. <code>ItemId</code>
     * objects) of the cached entries.
     *
     * @return a set view of the keys of the cached entries.
     */
    Set keySet();

    /**
     * Returns an unmodifiable collection view of the values (i.e.
     * <code>ItemState</code> objects) contained in this cache.
     *
     * @return a collection view of the values contained in this cache.
     */
    Collection values();
}
