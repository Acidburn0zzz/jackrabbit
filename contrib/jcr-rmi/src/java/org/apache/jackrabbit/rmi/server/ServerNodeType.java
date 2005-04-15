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
package org.apache.jackrabbit.rmi.server;

import java.rmi.RemoteException;

import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import org.apache.jackrabbit.rmi.remote.RemoteNodeDefinition;
import org.apache.jackrabbit.rmi.remote.RemoteNodeType;
import org.apache.jackrabbit.rmi.remote.RemotePropertyDefinition;

/**
 * Remote adapter for the JCR {@link javax.jcr.nodetype.NodeType NodeType}
 * interface. This class makes a local node type available as an RMI service
 * using the
 * {@link org.apache.jackrabbit.rmi.remote.RemoteNodeType RemoteNodeType}
 * interface.
 *
 * @author Jukka Zitting
 * @see javax.jcr.nodetype.NodeType
 * @see org.apache.jackrabbit.rmi.remote.RemoteNodeType
 */
public class ServerNodeType extends ServerObject implements RemoteNodeType {

    /** The adapted local node type. */
    private NodeType type;

    /**
     * Creates a remote adapter for the given local node type.
     *
     * @param type local node type
     * @param factory remote adapter factory
     * @throws RemoteException on RMI errors
     */
    public ServerNodeType(NodeType type, RemoteAdapterFactory factory)
            throws RemoteException {
        super(factory);
        this.type = type;
    }

    /** {@inheritDoc} */
    public String getName() throws RemoteException {
        return type.getName();
    }

    /** {@inheritDoc} */
    public boolean isMixin() throws RemoteException {
        return type.isMixin();
    }

    /** {@inheritDoc} */
    public boolean hasOrderableChildNodes() throws RemoteException {
        return type.hasOrderableChildNodes();
    }

    /** {@inheritDoc} */
    public RemoteNodeType[] getSupertypes() throws RemoteException {
        return getRemoteNodeTypeArray(type.getSupertypes());
    }

    /** {@inheritDoc} */
    public RemoteNodeType[] getDeclaredSupertypes() throws RemoteException {
        return getRemoteNodeTypeArray(type.getDeclaredSupertypes());
    }

    /** {@inheritDoc} */
    public boolean isNodeType(String type) throws RemoteException {
        return this.type.isNodeType(type);
    }

    /** {@inheritDoc} */
    public RemotePropertyDefinition[] getPropertyDefs() throws RemoteException {
        PropertyDefinition[] defs = type.getPropertyDefinitions();
        return getRemotePropertyDefArray(defs);
    }

    /** {@inheritDoc} */
    public RemotePropertyDefinition[] getDeclaredPropertyDefs()
            throws RemoteException {
        PropertyDefinition[] defs = type.getDeclaredPropertyDefinitions();
        return getRemotePropertyDefArray(defs);
    }

    /** {@inheritDoc} */
    public RemoteNodeDefinition[] getChildNodeDefs() throws RemoteException {
        return getRemoteNodeDefArray(type.getChildNodeDefinitions());
    }

    /** {@inheritDoc} */
    public RemoteNodeDefinition[] getDeclaredChildNodeDefs() throws RemoteException {
        return getRemoteNodeDefArray(type.getDeclaredChildNodeDefinitions());
    }

    /** {@inheritDoc} */
    public boolean canSetProperty(String name, Value value)
            throws RemoteException {
        return type.canSetProperty(name, value);
    }

    /** {@inheritDoc} */
    public boolean canSetProperty(String name, Value[] values)
            throws RemoteException {
        return type.canSetProperty(name, values);
    }

    /** {@inheritDoc} */
    public boolean canAddChildNode(String name) throws RemoteException {
        return type.canAddChildNode(name);
    }

    /** {@inheritDoc} */
    public boolean canAddChildNode(String name, String type)
            throws RemoteException {
        return this.type.canAddChildNode(name, type);
    }

    /** {@inheritDoc} */
    public boolean canRemoveItem(String name) throws RemoteException {
        return type.canRemoveItem(name);
    }

    /** {@inheritDoc} */
    public String getPrimaryItemName() throws RemoteException {
        return type.getPrimaryItemName();
    }

}
