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
package org.apache.jackrabbit.core.jndi;

import org.apache.jackrabbit.core.RepositoryImpl;
import org.apache.jackrabbit.core.config.RepositoryConfig;

import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * A referenceable and serializable content repository proxy.
 * This class implements the Proxy design pattern (GoF) for the
 * Jackrabbit Repository implementation. The proxy implementation
 * delays the instantiation of the actual Repository instance and
 * implements serialization and JNDI referenceability by keeping
 * track of the repository configuration parameters.
 * <p>
 * A BindableRepository instance contains the configuration file
 * and home directory paths of a Jackrabbit repository. The separate
 * {@link #init() init()} method is used to create a transient
 * {@link RepositoryImpl RepositoryImpl} instance to which all the
 * JCR API calls are delegated.
 * <p>
 * An instance of this class is normally always also initialized.
 * The uninitialized state is only used briefly during the static
 * {@link #create(String, String) create} method and during
 * serialization and JNDI "referenciation".
 */
class BindableRepository implements Repository, Referenceable, Serializable {

    /** The serialization UID of this class. */
    static final long serialVersionUID = -2298220550793843166L;

    /** The repository configuration file path. */
    private final String configFilePath;

    /** The repository home directory path. */
    private final String repHomeDir;

    /**
     * type of <code>configFilePath</code> reference address (@see <code>{@link Reference#get(String)}</code>
     */
    static final String CONFIGFILEPATH_ADDRTYPE = "configFilePath";
    /**
     * type of <code>repHomeDir</code> reference address (@see <code>{@link Reference#get(String)}</code>
     */
    static final String REPHOMEDIR_ADDRTYPE = "repHomeDir";

    /** The delegate repository instance. Created by {@link #init() init}. */
    private transient Repository delegatee;

    /**
     * Creates a BindableRepository instance with the given configuration
     * information, but does not create the underlying repository instance.
     *
     * @param configFilePath repository configuration file path
     * @param repHomeDir repository home directory path
     */
    private BindableRepository(String configFilePath, String repHomeDir) {
        this.configFilePath = configFilePath;
        this.repHomeDir = repHomeDir;
        delegatee = null;
    }

    /**
     * Creates an initialized BindableRepository instance using the given
     * configuration information.
     *
     * @param configFilePath repository configuration file path
     * @param repHomeDir repository home directory path
     * @return initialized repository instance
     * @throws RepositoryException if the repository cannot be created
     */
    static BindableRepository create(String configFilePath, String repHomeDir)
            throws RepositoryException {
        BindableRepository rep = new BindableRepository(configFilePath, repHomeDir);
        rep.init();
        return rep;
    }

    /**
     * Creates the underlying repository instance.
     *
     * @throws RepositoryException if the repository cannot be created
     */
    private void init() throws RepositoryException {
        RepositoryConfig config =
            RepositoryConfig.create(configFilePath, repHomeDir);
        delegatee = RepositoryImpl.create(config);
    }

    //-----------------------------------------------------------< Repository >

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public Session login(Credentials credentials, String workspaceName)
            throws LoginException, NoSuchWorkspaceException, RepositoryException {
        return delegatee.login(credentials, workspaceName);
    }

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public Session login(String workspaceName)
            throws LoginException, NoSuchWorkspaceException, RepositoryException {
        return delegatee.login(workspaceName);
    }

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public Session login() throws LoginException, RepositoryException {
        return delegatee.login();
    }

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public Session login(Credentials credentials)
            throws LoginException, RepositoryException {
        return delegatee.login(credentials);
    }

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public String getDescriptor(String key) {
        return delegatee.getDescriptor(key);
    }

    /**
     * Delegated to the underlying repository instance.
     * {@inheritDoc}
     */
    public String[] getDescriptorKeys() {
        return delegatee.getDescriptorKeys();
    }

    //--------------------------------------------------------< Referenceable >

    /**
     * Creates a JNDI reference for this content repository. The returned
     * reference holds the configuration information required to create a
     * copy of this instance.
     *
     * @return the created JNDI reference
     * @throws NamingException on JNDI errors
     */
    public Reference getReference() throws NamingException {
        Reference ref = new Reference(
                BindableRepository.class.getName(),
                BindableRepositoryFactory.class.getName(),
                null); // no classpath defined
        ref.add(new StringRefAddr(CONFIGFILEPATH_ADDRTYPE, configFilePath));
        ref.add(new StringRefAddr(REPHOMEDIR_ADDRTYPE, repHomeDir));
        return ref;
    }

    //-------------------------------------------------< Serializable support >

    /**
     * Serializes the repository configuration. The default serialization
     * mechanism is used, as the underlying delegate repository is referenced
     * using a transient variable.
     *
     * @param out the serialization stream
     * @throws IOException on IO errors
     * @see Serializable
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        // delegate to default implementation
        out.defaultWriteObject();
    }

    /**
     * Deserializes a repository instance. The repository configuration
     * is deserialized using the standard deserialization mechanism, and
     * the underlying delegate repository is created using the
     * {@link #init() init} method.
     *
     * @param in the serialization stream
     * @throws IOException if configuration information cannot be deserialized
     *                     or if the configured repository cannot be created
     * @throws ClassNotFoundException on deserialization errors
     */
    private void readObject(ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        // delegate deserialization to default implementation
        in.defaultReadObject();
        // initialize reconstructed instance
        try {
            init();
        } catch (RepositoryException re) {
            // failed to reinstantiate repository
            throw new IOException(re.getMessage());
        }
    }
    
    /**
     * Delegated to the underlying repository instance.
     */
    void shutdown() {
    	((RepositoryImpl) delegatee).shutdown() ;
    }
}
