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
package org.apache.jackrabbit.core.nodetype;

import org.apache.jackrabbit.core.nodetype.xml.NodeTypeReader;
import org.apache.jackrabbit.core.nodetype.xml.NodeTypeWriter;
import org.apache.jackrabbit.name.QName;

import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

/**
 * <code>NodeTypeDefStore</code> ...
 */
public class NodeTypeDefStore {

    /** Map of node type names to node type definitions. */
    private final HashMap ntDefs;

    /**
     * Empty default constructor.
     */
    public NodeTypeDefStore() throws RepositoryException {
        ntDefs = new HashMap();
    }

    /**
     * @param in
     * @throws IOException
     * @throws InvalidNodeTypeDefException
     */
    public void load(InputStream in)
            throws IOException, InvalidNodeTypeDefException,
            RepositoryException {
        NodeTypeDef[] types = NodeTypeReader.read(in);
        for (int i = 0; i < types.length; i++) {
            add(types[i]);
        }
    }

    /**
     * @param out
     * @param registry
     * @throws IOException
     * @throws RepositoryException
     */
    public void store(OutputStream out, NamespaceRegistry registry)
            throws IOException, RepositoryException {
        NodeTypeDef[] types = (NodeTypeDef[])
            ntDefs.values().toArray(new NodeTypeDef[ntDefs.size()]);
        NodeTypeWriter.write(out, types, registry);
    }

    /**
     * @param ntd
     */
    public void add(NodeTypeDef ntd) {
        ntDefs.put(ntd.getName(), ntd);
    }

    /**
     * @param name
     * @return
     */
    public boolean remove(QName name) {
        return (ntDefs.remove(name) != null);
    }

    /**
     *
     */
    public void removeAll() {
        ntDefs.clear();
    }

    /**
     * @param name
     * @return
     */
    public boolean contains(QName name) {
        return ntDefs.containsKey(name);
    }

    /**
     * @param name
     * @return
     */
    public NodeTypeDef get(QName name) {
        return (NodeTypeDef) ntDefs.get(name);
    }

    /**
     * @return
     */
    public Collection all() {
        return Collections.unmodifiableCollection(ntDefs.values());
    }
}
