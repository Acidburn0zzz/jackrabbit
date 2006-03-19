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
package org.apache.jackrabbit.core.config;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.fs.FileSystemException;
import org.apache.jackrabbit.core.fs.FileSystemPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Repository configuration. This configuration class is used to
 * create configured repository objects.
 * <p>
 * The contained configuration information are: the home directory and name
 * of the repository, the access manager, file system and versioning
 * configuration, repository index configuration, the workspace directory,
 * the default workspace name, and the workspace configuration template. In
 * addition the workspace configuration object keeps track of all configured
 * workspaces.
 */
public class RepositoryConfig {

    /** the default logger */
    private static Logger log = LoggerFactory.getLogger(RepositoryConfig.class);

    /** Name of the workspace configuration file. */
    private static final String WORKSPACE_XML = "workspace.xml";

    /**
     * Convenience method that wraps the configuration file name into an
     * {@link InputSource} and invokes the
     * {@link #create(InputSource, String)} method.
     *
     * @param file repository configuration file name
     * @param home repository home directory
     * @return repository configuration
     * @throws ConfigurationException on configuration errors
     * @see #create(InputSource, String)
     */
    public static RepositoryConfig create(String file, String home)
            throws ConfigurationException {
        URI uri = new File(file).toURI();
        return create(new InputSource(uri.toString()), home);
    }

    /**
     * Convenience method that wraps the configuration URI into an
     * {@link InputSource} and invokes the
     * {@link #create(InputSource, String)} method.
     *
     * @param uri repository configuration URI
     * @param home repository home directory
     * @return repository configuration
     * @throws ConfigurationException on configuration errors
     * @see #create(InputSource, String)
     */
    public static RepositoryConfig create(URI uri, String home)
            throws ConfigurationException {
        return create(new InputSource(uri.toString()), home);
    }

    /**
     * Convenience method that wraps the configuration input stream into an
     * {@link InputSource} and invokes the
     * {@link #create(InputSource, String)} method.
     *
     * @param input repository configuration input stream
     * @param home repository home directory
     * @return repository configuration
     * @throws ConfigurationException on configuration errors
     * @see #create(InputSource, String)
     */
    public static RepositoryConfig create(InputStream input, String home)
            throws ConfigurationException {
        return create(new InputSource(input), home);
    }

    /**
     * Parses the given repository configuration document and returns the
     * parsed and initialized repository configuration. The given repository
     * home directory path will be used as the ${rep.home} parser variable.
     * <p>
     * Note that in addition to parsing the repository configuration, this
     * method also initializes the configuration (creates the configured
     * directories, etc.). The {@link ConfigurationParser} class should be
     * used directly to just parse the configuration.
     *
     * @param xml repository configuration document
     * @param home repository home directory
     * @return repository configuration
     * @throws ConfigurationException on configuration errors
     */
    public static RepositoryConfig create(InputSource xml, String home)
            throws ConfigurationException {
        Properties variables = new Properties();
        variables.setProperty(
                ConfigurationParser.REPOSITORY_HOME_VARIABLE, home);
        ConfigurationParser parser = new ConfigurationParser(variables);

        RepositoryConfig config = parser.parseRepositoryConfig(xml);
        config.init();

        return config;
    }

    /**
     * map of workspace names and workspace configurations
     */
    private Map workspaces;

    /**
     * Repository home directory.
     */
    private final String home;

    /**
     * Repository name for a JAAS app-entry configuration.
     */
    private final String name;

    /**
     * Repository access manager configuration;
     */
    private final AccessManagerConfig amc;

    /**
     * Repository login module configuration. Optional, can be null
     */
    private final LoginModuleConfig lmc;

    /**
     * Repository file system configuration.
     */
    private final FileSystemConfig fsc;

    /**
     * Name of the default workspace.
     */
    private final String defaultWorkspace;

    /**
     * the default parser
     */
    private final ConfigurationParser parser;

    /**
     * Workspace physical root directory. This directory contains a subdirectory
     * for each workspace in this repository, i.e. the physical workspace home
     * directory. Each workspace is configured by a workspace configuration file
     * either contained in the workspace home directory or, optionally, located
     * in a subdirectory of {@link #workspaceConfigDirectory} within the
     * repository file system if such has been specified.
     */
    private final String workspaceDirectory;

    /**
     * Path to workspace configuration root directory within the
     * repository file system or null if none was specified.
     */
    private final String workspaceConfigDirectory;

    /**
     * Amount of time in seconds after which an idle workspace is automatically
     * shutdown.
     */
    private final int workspaceMaxIdleTime;

    /**
     * The workspace configuration template. Used in creating new workspace
     * configuration files.
     */
    private final Element template;

    /**
     * Repository versioning configuration.
     */
    private final VersioningConfig vc;

    /**
     * Optional search configuration for system search manager.
     */
    private final SearchConfig sc;

    /**
     * Creates a repository configuration object.
     *
     * @param template workspace configuration template
     * @param home repository home directory
     * @param name repository name for a JAAS app-entry configuration
     * @param amc access manager configuration
     * @param lmc login module configuration (can be <code>null</code>)
     * @param fsc file system configuration
     * @param workspaceDirectory workspace root directory
     * @param workspaceConfigDirectory optional workspace configuration directory
     * @param workspaceMaxIdleTime maximum workspace idle time in seconds
     * @param defaultWorkspace name of the default workspace
     * @param vc versioning configuration
     * @param sc search configuration for system search manager.
     * @param parser the ConfigurationParser that servers as config factory
     */
    public RepositoryConfig(String home, String name,
            AccessManagerConfig amc, LoginModuleConfig lmc, FileSystemConfig fsc,
            String workspaceDirectory, String workspaceConfigDirectory,
            String defaultWorkspace, int workspaceMaxIdleTime,
            Element template, VersioningConfig vc, SearchConfig sc,
            ConfigurationParser parser) {
        workspaces = new HashMap();
        this.home = home;
        this.name = name;
        this.amc = amc;
        this.lmc = lmc;
        this.fsc = fsc;
        this.workspaceDirectory = workspaceDirectory;
        this.workspaceConfigDirectory = workspaceConfigDirectory;
        this.workspaceMaxIdleTime = workspaceMaxIdleTime;
        this.defaultWorkspace = defaultWorkspace;
        this.template = template;
        this.vc = vc;
        this.sc = sc;
        this.parser = parser;
    }

    /**
     * Initializes the repository configuration. This method loads the
     * configurations for all available workspaces.
     *
     * @throws ConfigurationException on initialization errors
     * @throws IllegalStateException if the repository configuration has already
     *                               been initialized
     */
    public void init() throws ConfigurationException, IllegalStateException {
        if (!workspaces.isEmpty()) {
            throw new IllegalStateException(
                    "Repository configuration has already been initialized.");
        }

        // Get the physical workspace root directory (create it if not found)
        File directory = new File(workspaceDirectory);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // Get all workspace subdirectories
        if (workspaceConfigDirectory != null) {
            // a configuration directoy had been specified; search for
            // workspace configurations in virtual repository file system
            // rather than in physical workspace root directory on disk
            FileSystem fs = fsc.createFileSystem();
            try {
                if (!fs.exists(workspaceConfigDirectory)) {
                    fs.createFolder(workspaceConfigDirectory);
                } else {
                    String[] dirNames = fs.listFolders(workspaceConfigDirectory);
                    for (int i = 0; i < dirNames.length; i++) {
                        String configDir = workspaceConfigDirectory
                                + FileSystem.SEPARATOR + dirNames[i];
                        WorkspaceConfig wc = loadWorkspaceConfig(fs, configDir);
                        if (wc != null) {
                            addWorkspaceConfig(wc);
                        }
                    }

                }
            } catch (FileSystemException e) {
                throw new ConfigurationException(
                        "error while loading workspace configurations from path "
                        + workspaceConfigDirectory, e);
            } finally {
                try {
                    fs.close();
                } catch (FileSystemException ignore) {
                }
            }
        } else {
            // search for workspace configurations in physical workspace root
            // directory on disk
            File[] files = directory.listFiles();
            if (files == null) {
                throw new ConfigurationException(
                        "Invalid workspace root directory: " + workspaceDirectory);
            }

            for (int i = 0; i < files.length; i++) {
                WorkspaceConfig wc = loadWorkspaceConfig(files[i]);
                if (wc != null) {
                    addWorkspaceConfig(wc);
                }
            }
        }
        if (!workspaces.containsKey(defaultWorkspace)) {
            if (!workspaces.isEmpty()) {
                log.warn("Potential misconfiguration. No configuration found "
                        + "for default workspace: " + defaultWorkspace);
            }
            // create initial default workspace
            createWorkspaceConfig(defaultWorkspace);
        }
    }

    /**
     * Attempts to load a workspace configuration from the given physical
     * workspace subdirectory. If the directory contains a valid workspace
     * configuration file, then the configuration is parsed and returned as a
     * workspace configuration object. The returned configuration object has not
     * been initialized.
     * <p>
     * This method returns <code>null</code>, if the given directory does
     * not exist or does not contain a workspace configuration file. If an
     * invalid configuration file is found, then a
     * {@link ConfigurationException ConfigurationException} is thrown.
     *
     * @param directory physical workspace configuration directory on disk
     * @return workspace configuration
     * @throws ConfigurationException if the workspace configuration is invalid
     */
    private WorkspaceConfig loadWorkspaceConfig(File directory)
            throws ConfigurationException {
        try {
            File file = new File(directory, WORKSPACE_XML);
            InputSource xml = new InputSource(new FileReader(file));
            xml.setSystemId(file.toURI().toString());

            Properties variables = new Properties();
            variables.setProperty(
                    ConfigurationParser.WORKSPACE_HOME_VARIABLE,
                    directory.getPath());
            ConfigurationParser localParser = parser.createSubParser(variables);
            return localParser.parseWorkspaceConfig(xml);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * Attempts to load a workspace configuration from the given workspace
     * subdirectory within the repository file system. If the directory contains
     * a valid workspace configuration file, then the configuration is parsed
     * and returned as a workspace configuration object. The returned
     * configuration object has not been initialized.
     * <p>
     * This method returns <code>null</code>, if the given directory does
     * not exist or does not contain a workspace configuration file. If an
     * invalid configuration file is found, then a
     * {@link ConfigurationException ConfigurationException} is thrown.
     *
     * @param fs virtual file system where to look for the configuration file
     * @param configDir workspace configuration directory in virtual file system
     * @return workspace configuration
     * @throws ConfigurationException if the workspace configuration is invalid
     */
    private WorkspaceConfig loadWorkspaceConfig(FileSystem fs, String configDir)
            throws ConfigurationException {
        Reader configReader = null;
        try {
            String configPath = configDir + FileSystem.SEPARATOR + WORKSPACE_XML;
            if (!fs.exists(configPath)) {
                // no configuration file in this directory
                return null;
            }

            configReader = new InputStreamReader(fs.getInputStream(configPath));
            InputSource xml = new InputSource(configReader);
            xml.setSystemId(configPath);

            // the physical workspace home directory (TODO encode name?)
            File homeDir = new File(
                    workspaceDirectory, FileSystemPathUtil.getName(configDir));
            if (!homeDir.exists()) {
                homeDir.mkdir();
            }
            Properties variables = new Properties();
            variables.setProperty(
                    ConfigurationParser.WORKSPACE_HOME_VARIABLE,
                    homeDir.getPath());
            ConfigurationParser localParser = parser.createSubParser(variables);
            return localParser.parseWorkspaceConfig(xml);
        } catch (FileSystemException e) {
            throw new ConfigurationException("Failed to load workspace configuration", e);
        } finally {
            if (configReader != null) {
                try {
                    configReader.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Adds the given workspace configuration to the repository.
     *
     * @param wc workspace configuration
     * @throws ConfigurationException if a workspace with the same name
     *                                already exists
     */
    private void addWorkspaceConfig(WorkspaceConfig wc)
            throws ConfigurationException {
        String name = wc.getName();
        if (!workspaces.containsKey(name)) {
            workspaces.put(name, wc);
        } else {
            throw new ConfigurationException(
                    "Duplicate workspace configuration: " + name);
        }
    }

    /**
     * Creates a new workspace configuration with the specified name and the
     * specified workspace <code>template</.
     * <p/>
     * This method creates a workspace configuration subdirectory,
     * copies the workspace configuration template into it, and finally
     * adds the created workspace configuration to the repository.
     * The initialized workspace configuration object is returned to
     * the caller.
     *
     * @param name workspace name
     * @param template the workspace template
     * @return created workspace configuration
     * @throws ConfigurationException if creating the workspace configuration
     *                                failed
     */
    private synchronized WorkspaceConfig internalCreateWorkspaceConfig(String name,
                                                                       Element template)
            throws ConfigurationException {

        // The physical workspace home directory on disk (TODO encode name?)
        File directory = new File(workspaceDirectory, name);

        // Create the physical workspace directory, fail if it exists
        // or cannot be created
        if (!directory.mkdir()) {
            if (directory.exists()) {
                throw new ConfigurationException(
                        "Workspace directory already exists: " + name);
            } else {
                throw new ConfigurationException(
                        "Failed to create workspace directory: " + name);
            }
        }

        FileSystem virtualFS;
        if (workspaceConfigDirectory != null) {
            // a configuration directoy had been specified;
            // workspace configurations are maintained in
            // virtual repository file system
            virtualFS = fsc.createFileSystem();
        } else {
            // workspace configurations are maintained on disk
            virtualFS = null;
        }
        try {
            Writer configWriter;

            // get a writer for the workspace configuration file
            if (virtualFS != null) {
                // a configuration directoy had been specified; create workspace
                // configuration in virtual repository file system rather than
                // on disk
                String configDir = workspaceConfigDirectory
                        + FileSystem.SEPARATOR + name;
                String configFile = configDir + FileSystem.SEPARATOR + WORKSPACE_XML;
                try {
                    // Create the directory
                    virtualFS.createFolder(configDir);
                    configWriter = new OutputStreamWriter(
                            virtualFS.getOutputStream(configFile));
                } catch (FileSystemException e) {
                    throw new ConfigurationException(
                            "failed to create workspace configuration at path "
                            + configFile, e);
                }
            } else {
                File file = new File(directory, WORKSPACE_XML);
                try {
                    configWriter = new FileWriter(file);
                } catch (IOException e) {
                    throw new ConfigurationException(
                            "failed to create workspace configuration at path "
                            + file.getPath(), e);
                }
            }

            // Create the workspace.xml file using the configuration template and
            // the configuration writer.
            try {
                template.setAttribute("name", name);

                TransformerFactory factory = TransformerFactory.newInstance();
                Transformer transformer = factory.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.transform(
                        new DOMSource(template), new StreamResult(configWriter));
            } catch (TransformerConfigurationException e) {
                throw new ConfigurationException(
                        "Cannot create a workspace configuration writer", e);
            } catch (TransformerException e) {
                throw new ConfigurationException(
                        "Cannot create a workspace configuration file", e);
            } finally {
                try {
                    configWriter.close();
                } catch (IOException ignore) {
                }
            }

            // Load the created workspace configuration.
            WorkspaceConfig wc;
            if (virtualFS != null) {
                String configDir = workspaceConfigDirectory
                        + FileSystem.SEPARATOR + name;
                wc = loadWorkspaceConfig(virtualFS, configDir);
            } else {
                wc = loadWorkspaceConfig(directory);
            }
            if (wc != null) {
                addWorkspaceConfig(wc);
                return wc;
            } else {
                throw new ConfigurationException(
                        "Failed to load the created configuration for workspace "
                        + name + ".");
            }
        } finally {
            try {
                if (virtualFS != null) {
                    virtualFS.close();
                }
            } catch (FileSystemException ignore) {
            }
        }
    }

    /**
     * Creates a new workspace configuration with the specified name.
     * This method creates a workspace configuration subdirectory,
     * copies the workspace configuration template into it, and finally
     * adds the created workspace configuration to the repository.
     * The initialized workspace configuration object is returned to
     * the caller.
     *
     * @param name workspace name
     * @return created workspace configuration
     * @throws ConfigurationException if creating the workspace configuration
     *                                failed
     */
    public WorkspaceConfig createWorkspaceConfig(String name)
            throws ConfigurationException {
        // use workspace template from repository.xml
        return internalCreateWorkspaceConfig(name, template);
    }

    /**
     * Creates a new workspace configuration with the specified name. This
     * method uses the provided workspace <code>template</code> to create the
     * repository config instead of the template that is present in the
     * repository configuration.
     * <p/>
     * This method creates a workspace configuration subdirectory,
     * copies the workspace configuration template into it, and finally
     * adds the created workspace configuration to the repository.
     * The initialized workspace configuration object is returned to
     * the caller.
     *
     * @param name workspace name
     * @param template the workspace template
     * @return created workspace configuration
     * @throws ConfigurationException if creating the workspace configuration
     *                                failed
     */
    public WorkspaceConfig createWorkspaceConfig(String name,
                                                 InputSource template)
            throws ConfigurationException {
        ConfigurationParser parser = new ConfigurationParser(new Properties());
        Element workspaceTemplate = parser.parseXML(template);
        return internalCreateWorkspaceConfig(name, workspaceTemplate);
    }

    /**
     * Returns the repository home directory.
     *
     * @return repository home directory
     */
    public String getHomeDir() {
        return home;
    }

    /**
     * Returns the repository file system configuration.
     *
     * @return file system configuration
     */
    public FileSystemConfig getFileSystemConfig() {
        return fsc;
    }

    /**
     * Returns the repository name. The repository name can be used for
     * JAAS app-entry configuration.
     *
     * @return repository name
     */
    public String getAppName() {
        return name;
    }

    /**
     * Returns the repository access manager configuration.
     *
     * @return access manager configuration
     */
    public AccessManagerConfig getAccessManagerConfig() {
        return amc;
    }

    /**
     * Returns the repository login module configuration.
     *
     * @return login module configuration, or <code>null</code> if standard
     *         JAAS mechanism should be used.
     */
    public LoginModuleConfig getLoginModuleConfig() {
        return lmc;
    }

    /**
     * Returns the workspace root directory.
     *
     * @return workspace root directory
     */
    public String getWorkspacesConfigRootDir() {
        return workspaceDirectory;
    }

    /**
     * Returns the name of the default workspace.
     *
     * @return name of the default workspace
     */
    public String getDefaultWorkspaceName() {
        return defaultWorkspace;
    }

    /**
     * Returns the amount of time in seconds after which an idle workspace is
     * automatically shutdown. If zero then idle workspaces will never be
     * automatically shutdown.
     *
     * @return maximum workspace idle time in seconds
     */
    public int getWorkspaceMaxIdleTime() {
        return workspaceMaxIdleTime;
    }

    /**
     * Returns all workspace configurations.
     *
     * @return workspace configurations
     */
    public Collection getWorkspaceConfigs() {
        return workspaces.values();
    }

    /**
     * Returns the configuration of the specified workspace.
     *
     * @param name workspace name
     * @return workspace configuration, or <code>null</code> if the named
     *         workspace does not exist
     */
    public WorkspaceConfig getWorkspaceConfig(String name) {
        return (WorkspaceConfig) workspaces.get(name);
    }

    /**
     * Returns the repository versioning configuration.
     *
     * @return versioning configuration
     */
    public VersioningConfig getVersioningConfig() {
        return vc;
    }

    /**
     * Returns the system search index configuration. Returns
     * <code>null</code> if no search index has been configured.
     *
     * @return search index configuration, or <code>null</code>
     */
    public SearchConfig getSearchConfig() {
        return sc;
    }
}
