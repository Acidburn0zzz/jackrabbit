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
package org.apache.jackrabbit.core.config;

import org.apache.jackrabbit.core.fs.FileSystem;

import java.io.File;

/**
 * Versioning configuration. This configuration class is used to
 * create configured versioning objects.
 * <p>
 * The contained configuration information are: the home directory,
 * the file system implementation, and the persistence manager
 * implementation.
 *
 * @see RepositoryConfig#getVersioningConfig()
 */
public class VersioningConfig {

    /**
     * Versioning home directory.
     */
    private final String home;

    /**
     * Versioning file system configuration.
     */
    private final FileSystemConfig fsc;

    /**
     * Versioning persistence manager configuration.
     */
    private final PersistenceManagerConfig pmc;

    /**
     * Creates a versioning configuration object.
     *
     * @param home home directory
     * @param fsc file system configuration
     * @param pmc persistence manager configuration
     */
    public VersioningConfig(
            String home, FileSystemConfig fsc, PersistenceManagerConfig pmc) {
        this.home = home;
        this.fsc = fsc;
        this.pmc = pmc;
    }

    /**
     * Initializes the versioning file system.
     *
     * @throws ConfigurationException on file system configuration errors
     */
    public void init() throws ConfigurationException {
        fsc.init();
    }

    /**
     * Returns the versioning home directory.
     *
     * @return versioning home directory
     */
    public File getHomeDir() {
        return new File(home);
    }

    /**
     * Returns the versioning file system implementation.
     *
     * @return file system implementation
     */
    public FileSystem getFileSystem() {
        return fsc.getFileSystem();
    }

    /**
     * Returns the versioning persistence manager configuration.
     *
     * @return persistence manager configuration
     */
    public PersistenceManagerConfig getPersistenceManagerConfig() {
        return pmc;
    }

}
