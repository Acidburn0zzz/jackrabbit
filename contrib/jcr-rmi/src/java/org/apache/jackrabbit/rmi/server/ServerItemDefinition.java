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

import javax.jcr.nodetype.ItemDefinition;

import org.apache.jackrabbit.rmi.remote.RemoteItemDefinition;
import org.apache.jackrabbit.rmi.remote.RemoteNodeType;

/**
 * Remote adapter for the JCR {@link javax.jcr.nodetype.ItemDefinition ItemDefinition}
 * interface. This class makes a local item definition available as an
 * RMI service using the
 * {@link org.apache.jackrabbit.rmi.remote.RemoteItemDefinition RemoteItemDefinition}
 * interface. Used mainly as the base class for the
 * {@link org.apache.jackrabbit.rmi.server.ServerPropertyDefinition ServerPropertyDefinition}
 * and
 * {@link org.apache.jackrabbit.rmi.server.ServerNodeDefinition ServerNodeDefinition}
 * adapters.
 *
 * @author Jukka Zitting
 * @see javax.jcr.nodetype.ItemDefinition
 * @see org.apache.jackrabbit.rmi.remote.RemoteItemDefinition
 */
public class ServerItemDefinition extends ServerObject implements RemoteItemDefinition {

    /** The adapted local item definition. */
    private ItemDefinition def;

    /**
     * Creates a remote adapter for the given local item definition.
     *
     * @param def local item definition
     * @param factory remote adapter factory
     * @throws RemoteException on RMI errors
     */
    public ServerItemDefinition(ItemDefinition def, RemoteAdapterFactory factory)
            throws RemoteException {
        super(factory);
        this.def = def;
    }

    /** {@inheritDoc} */
    public RemoteNodeType getDeclaringNodeType() throws RemoteException {
        return getFactory().getRemoteNodeType(def.getDeclaringNodeType());
    }

    /** {@inheritDoc} */
    public String getName() throws RemoteException {
        return def.getName();
    }

    /** {@inheritDoc} */
    public boolean isAutoCreated() throws RemoteException {
        return def.isAutoCreated();
    }

    /** {@inheritDoc} */
    public boolean isMandatory() throws RemoteException {
        return def.isMandatory();
    }

    /** {@inheritDoc} */
    public int getOnParentVersion() throws RemoteException {
        return def.getOnParentVersion();
    }

    /** {@inheritDoc} */
    public boolean isProtected() throws RemoteException {
        return def.isProtected();
    }

}
