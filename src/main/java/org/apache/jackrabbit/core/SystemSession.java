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
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.core.config.WorkspaceConfig;
import org.apache.jackrabbit.core.security.AMContext;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.core.security.SystemPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.RepositoryException;
import javax.security.auth.Subject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A <code>SystemSession</code> ...
 */
class SystemSession extends SessionImpl {

    private static Logger log = LoggerFactory.getLogger(SystemSession.class);

    /**
     * Package private factory method
     *
     * @param rep
     * @param wspConfig
     * @return
     * @throws RepositoryException
     */
    static SystemSession create(RepositoryImpl rep, WorkspaceConfig wspConfig)
            throws RepositoryException {
        // create subject with SystemPrincipal
        Set principals = new HashSet();
        principals.add(new SystemPrincipal());
        Subject subject =
                new Subject(true, principals, Collections.EMPTY_SET,
                        Collections.EMPTY_SET);
        return new SystemSession(rep, subject, wspConfig);
    }

    /**
     * private constructor
     *
     * @param rep
     * @param wspConfig
     */
    private SystemSession(RepositoryImpl rep, Subject subject,
                          WorkspaceConfig wspConfig)
            throws RepositoryException {
        super(rep, subject, wspConfig);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Overridden in order to create custom access manager
     *
     * @return access manager for system session
     * @throws AccessDeniedException is never thrown
     * @throws RepositoryException   is never thrown
     */
    protected AccessManager createAccessManager(Subject subject,
                                                HierarchyManager hierMgr)
            throws AccessDeniedException, RepositoryException {
        /**
         * use own AccessManager implementation rather than relying on
         * configurable AccessManager to handle SystemPrincipal privileges
         * correctly
         */
        return new SystemAccessManager();
        //return super.createAccessManager(subject, hierMgr);
    }

    //--------------------------------------------------------< inner classes >
    private class SystemAccessManager implements AccessManager {

        SystemAccessManager() {
        }

        //----------------------------------------------------< AccessManager >
        /**
         * {@inheritDoc}
         *
         * @throws AccessDeniedException is never thrown
         * @throws Exception             is never thrown
         */
        public void init(AMContext context)
                throws AccessDeniedException, Exception {
            // nop
        }

        /**
         * {@inheritDoc}
         */
        public void close() throws Exception {
            // nop
        }

        /**
         * {@inheritDoc}
         *
         * @throws AccessDeniedException is never thrown
         * @throws ItemNotFoundException is never thrown
         * @throws RepositoryException   is never thrown
         */
        public void checkPermission(ItemId id, int permissions)
                throws AccessDeniedException, ItemNotFoundException,
                RepositoryException {
            // allow everything
        }

        /**
         * {@inheritDoc}
         *
         * @return always <code>true</code>
         * @throws ItemNotFoundException is never thrown
         * @throws RepositoryException   is never thrown
         */
        public boolean isGranted(ItemId id, int permissions)
                throws ItemNotFoundException, RepositoryException {
            // allow everything
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * @return always <code>true</code>
         * @throws NoSuchWorkspaceException is never thrown
         * @throws RepositoryException      is never thrown
         */
        public boolean canAccess(String workspaceName)
                throws NoSuchWorkspaceException, RepositoryException {
            return true;
        }
    }
}
