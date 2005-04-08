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

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * <code>HierarchyManager</code> ...
 */
public interface HierarchyManager {

    /**
     * @param path
     * @return
     * @throws PathNotFoundException
     * @throws RepositoryException
     */
    public ItemId resolvePath(Path path) throws PathNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public Path getPath(ItemId id) throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public QName getName(ItemId id) throws ItemNotFoundException, RepositoryException;

    /**
     * Returns the depth of the specified item which is equivalent to
     * <code>getPath(id).getAncestorCount()</code>.  
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public int getDepth(ItemId id) throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public Path[] getAllPaths(ItemId id) throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @param includeZombies
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public Path[] getAllPaths(ItemId id, boolean includeZombies)
            throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public NodeId[] listParents(ItemId id) throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public ItemId[] listChildren(NodeId id) throws ItemNotFoundException, RepositoryException;

    /**
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public ItemId[] listZombieChildren(NodeId id) throws ItemNotFoundException, RepositoryException;
}
