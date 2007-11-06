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
package org.apache.jackrabbit.spi2jcr;

import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.spi.NameFactory;
import org.apache.jackrabbit.spi.PathFactory;
import org.apache.jackrabbit.spi.Subscription;
import org.apache.jackrabbit.spi.EventFilter;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.namespace.NamespaceResolver;
import org.apache.jackrabbit.conversion.NamePathResolver;
import org.apache.jackrabbit.conversion.ParsingNameResolver;
import org.apache.jackrabbit.conversion.NameResolver;
import org.apache.jackrabbit.conversion.PathResolver;
import org.apache.jackrabbit.conversion.ParsingPathResolver;
import org.apache.jackrabbit.conversion.DefaultNamePathResolver;
import org.apache.jackrabbit.namespace.AbstractNamespaceResolver;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Session;
import javax.jcr.RepositoryException;
import javax.jcr.Credentials;
import java.io.ObjectInputStream;
import java.io.ByteArrayInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Collections;

/**
 * <code>SessionInfoImpl</code> implements a session info based on a JCR
 * {@link Session}.
 */
class SessionInfoImpl implements SessionInfo {

    /**
     * The underlying JCR session.
     */
    private final Session session;

    /**
     * The namespace resolver for this session info.
     */
    private final NamePathResolver resolver;

    /**
     * A copy of the credentials that were used to obtain the JCR session.
     */
    private Credentials credentials;

    /**
     * The subscriptions that are currently in place for this session info.
     */
    private List subscriptions = Collections.EMPTY_LIST;

    /**
     * Monitor object for subscription changes.
     */
    private Object subscriptionChange = new Object();

    /**
     * Creates a new session info based on the given <code>session</code>.
     *
     * @param session     the JCR session.
     * @param credentials a copy of the credentials that were used to obtain the
     * @param nameFactory
     * @param pathFactory
     * @throws RepositoryException 
     */
    SessionInfoImpl(Session session, Credentials credentials,
                    NameFactory nameFactory, PathFactory pathFactory) throws RepositoryException {
        this.session = session;
        this.credentials = credentials;
        
        final NamespaceRegistry nsReg = session.getWorkspace().getNamespaceRegistry();
        final NamespaceResolver nsResolver = new AbstractNamespaceResolver() {
            public String getPrefix(String uri) throws NamespaceException {
                try {
                    return nsReg.getPrefix(uri);
                }
                catch (RepositoryException e) {
                    // should never get here...
                    throw new NamespaceException("internal error: failed to resolve namespace uri", e);
                }
            }

            public String getURI(String prefix) throws NamespaceException {
                try {
                    return nsReg.getURI(prefix);
                }
                catch (RepositoryException e) {
                    // should never get here...
                    throw new NamespaceException("internal error: failed to resolve namespace prefix", e);
                }
            }
        };

        final NameResolver nResolver = new ParsingNameResolver(nameFactory, nsResolver);
        final PathResolver pResolver = new ParsingPathResolver(pathFactory, nResolver);

        this.resolver = new DefaultNamePathResolver(nResolver, pResolver);
    }

    /**
     * @return the underlying session.
     */
    Session getSession() {
        return session;
    }

    /**
     * @return the namespace resolver for this session info.
     */
    NamePathResolver getNamePathResolver() {
        return resolver;
    }

    /**
     * @return credentials that were used to obtain this session info.
     * @throws RepositoryException if the credentials cannot be duplicated.
     */
    Credentials getCredentials() throws RepositoryException {
        // return a duplicate
        return duplicateCredentials(credentials);
    }

    Collection getSubscriptions() {
        synchronized (subscriptionChange) {
            return subscriptions;
        }
    }

    /**
     * Creates a subscriptions for this session info.
     *
     * @param idFactory the id factory.
     * @param filters the initial list of filters.
     * @return a subscription.
     * @throws RepositoryException
     */
    Subscription createSubscription(IdFactory idFactory, EventFilter[] filters)
            throws RepositoryException {
        synchronized (subscriptionChange) {
            List tmp = new ArrayList(subscriptions);
            EventSubscription s = new EventSubscription(idFactory, this, filters);
            tmp.add(s);
            subscriptions = Collections.unmodifiableList(tmp);
            return s;
        }
    }

    /**
     * Removes the subscription from this session info is it exists.
     *
     * @param subscription the subscription to remove.
     */
    void removeSubscription(Subscription subscription) {
        synchronized (subscriptionChange) {
            List tmp = new ArrayList(subscriptions);
            tmp.remove(subscription);
            subscriptions = Collections.unmodifiableList(tmp);
        }
    }

    /**
     * Returns a duplicate of the passed credentials
     *
     * @param credentials the credentials to duplicate.
     * @return a duplicate of the passed credentials.
     * @throws RepositoryException if an error occurs while duplicating the
     *                             credentials.
     */
    public static Credentials duplicateCredentials(Credentials credentials)
            throws RepositoryException {
        if (credentials == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ObjectOutputStream oOut = new ObjectOutputStream(out);
            oOut.writeObject(credentials);
            oOut.close();

            ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
            ObjectInputStream oIn = new ObjectInputStream(in);
            return (Credentials) oIn.readObject();
        } catch (Exception e) {
            throw new RepositoryException(e);
        }
    }

    //--------------------------------------------------------< SessionInfo >---

    /**
     * @inheritDoc
     */
    public String getUserID() {
        return session.getUserID();
    }

    /**
     * @inheritDoc
     */
    public String getWorkspaceName() {
        return session.getWorkspace().getName();
    }

    /**
     * @inheritDoc
     */
    public String[] getLockTokens() {
        return session.getLockTokens();
    }

    /**
     * @inheritDoc
     */
    public void addLockToken(String lockToken) {
        session.addLockToken(lockToken);
    }

    /**
     * @inheritDoc
     */
    public void removeLockToken(String lockToken) {
        session.removeLockToken(lockToken);
    }
}
