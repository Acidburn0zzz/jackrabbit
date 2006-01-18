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

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration parser. This class is used to parse the repository and
 * workspace configuration files. Each configuration parser instance
 * contains a set of parser variables that are used for variable replacement
 * in the configuration file.
 * <p>
 * The following code sample outlines the usage of this class:
 * <pre>
 *     Properties variables = ...; // parser variables
 *     ConfigurationParser parser = new ConfigurationParser(variables);
 *     RepositoryConfig rc = parser.parseRepositoryConfig(...);
 *     WorkspaceConfig wc = parser.parseWorkspaceConfig(...);
 * </pre>
 * <p>
 * Note that the configuration objects returned by this parser are not
 * initialized. The caller needs to initialize the configuration objects
 * before using them.
 */
public class ConfigurationParser {

    /** Name of the repository home directory parser variable. */
    public static final String REPOSITORY_HOME_VARIABLE = "rep.home";

    /** Name of the workspace home directory parser variable. */
    public static final String WORKSPACE_HOME_VARIABLE = "wsp.home";

    /** Name of the repository name parser variable. */
    public static final String WORKSPACE_NAME_VARIABLE = "wsp.name";

    /** Name of the security configuration element. */
    public static final String SECURITY_ELEMENT = "Security";

    /** Name of the access manager configuration element. */
    public static final String ACCESS_MANAGER_ELEMENT = "AccessManager";

    /** Name of the login module configuration element. */
    public static final String LOGIN_MODULE_ELEMENT = "LoginModule";

    /** Name of the general workspace configuration element. */
    public static final String WORKSPACES_ELEMENT = "Workspaces";

    /** Name of the workspace configuration element. */
    public static final String WORKSPACE_ELEMENT = "Workspace";

    /** Name of the versioning configuration element. */
    public static final String VERSIONING_ELEMENT = "Versioning";

    /** Name of the file system configuration element. */
    public static final String FILE_SYSTEM_ELEMENT = "FileSystem";

    /** Name of the persistence manager configuration element. */
    public static final String PERSISTENCE_MANAGER_ELEMENT =
        "PersistenceManager";

    /** Name of the search index configuration element. */
    public static final String SEARCH_INDEX_ELEMENT = "SearchIndex";

    /** Name of the bean parameter configuration element. */
    public static final String PARAM_ELEMENT = "param";

    /** Name of the application name configuration attribute. */
    public static final String APP_NAME_ATTRIBUTE = "appName";

    /** Name of the root path configuration attribute. */
    public static final String ROOT_PATH_ATTRIBUTE = "rootPath";

    /** Name of the config root path configuration attribute. */
    public static final String CONFIG_ROOT_PATH_ATTRIBUTE = "configRootPath";

    /** Name of the maximum idle time configuration attribute. */
    public static final String MAX_IDLE_TIME_ATTRIBUTE = "maxIdleTime";

    /** Name of the default workspace configuration attribute. */
    public static final String DEFAULT_WORKSPACE_ATTRIBUTE =
        "defaultWorkspace";

    /** Name of the bean implementation class configuration attribute. */
    public static final String CLASS_ATTRIBUTE = "class";

    /** Name of the bean parameter name configuration attribute. */
    public static final String NAME_ATTRIBUTE = "name";

    /** Name of the bean parameter value configuration attribute. */
    public static final String VALUE_ATTRIBUTE = "value";

    /** Name of the default search index implementation class. */
    public static final String DEFAULT_QUERY_HANDLER =
            "org.apache.jackrabbit.core.query.lucene.SearchIndex";

    /**
     * The configuration parser variables. These name-value pairs
     * are used to substitute <code>${...}</code> variable references
     * with context-dependent values in the configuration.
     *
     * @see #replaceVariables(String)
     */
    private final Properties variables;

    /**
     * Creates a new configuration parser with the given parser variables.
     *
     * @param variables parser variables
     */
    public ConfigurationParser(Properties variables) {
        this.variables = variables;
    }

    /**
     * Returns the variables.
     * @return the variables.
     */
    public Properties getVariables() {
        return variables;
    }

    /**
     * Parses repository configuration. Repository configuration uses the
     * following format:
     * <pre>
     *   &lt;Repository&gt;
     *     &lt;FileSystem ...&gt;
     *     &lt;Security appName="..."&gt;
     *       &lt;AccessManager ...&gt;
     *       &lt;LoginModule ... (optional)&gt;
     *     &lt;/Security&gt;
     *     &lt;Workspaces rootPath="..." defaultWorkspace="..."/&gt;
     *     &lt;Workspace ...&gt;
     *     &lt;Versioning ...&gt;
     *   &lt;/Repository&gt;
     * </pre>
     * <p>
     * The <code>FileSystem</code> element is a
     * {@link #parseBeanConfig(Element,String) bean configuration} element,
     * that specifies the file system implementation for storing global
     * repository information. The <code>Security</code> element contains
     * an <code>AccessManager</code> bean configuration element and the
     * JAAS name of the repository application. The <code>Workspaces</code>
     * element contains general workspace parameters, and the
     * <code>Workspace</code> element is a template for the individual
     * workspace configuration files. The <code>Versioning</code> element
     * contains
     * {@link #parseVersioningConfig(Element) versioning configuration} for
     * the repository.
     * <p>
     * In addition to the configured information, the returned repository
     * configuration object also contains the repository home directory path
     * that is given as the ${rep.home} parser variable. Note that the
     * variable <em>must</em> be available for the configuration document to
     * be correctly parsed.
     * <p>
     * {@link #replaceVariables(String) Variable replacement} is performed
     * on the security application name attribute, the general workspace
     * configuration attributes, and on the file system, access manager,
     * and versioning configuration information.
     * <p>
     * Note that the returned repository configuration object has not been
     * initialized.
     *
     * @param xml repository configuration document
     * @return repository configuration
     * @throws ConfigurationException if the configuration is broken
     * @see #parseBeanConfig(Element, String)
     * @see #parseVersioningConfig(Element)
     */
    public RepositoryConfig parseRepositoryConfig(InputSource xml)
            throws ConfigurationException {
        Element root = parseXML(xml);

        // Repository home directory
        String home = variables.getProperty(REPOSITORY_HOME_VARIABLE);

        // File system implementation
        FileSystemConfig fsc =
            new FileSystemConfig(parseBeanConfig(root, FILE_SYSTEM_ELEMENT));

        // Security configuration and access manager implementation
        Element security = getElement(root, SECURITY_ELEMENT);
        String appName = getAttribute(security, APP_NAME_ATTRIBUTE);
        AccessManagerConfig amc = new AccessManagerConfig(
                parseBeanConfig(security, ACCESS_MANAGER_ELEMENT));

        // Optional login module
        Element loginModule = getElement(security, LOGIN_MODULE_ELEMENT, false);

        LoginModuleConfig lmc = null;
        if (loginModule != null) {
            lmc = new LoginModuleConfig(parseBeanConfig(security, LOGIN_MODULE_ELEMENT));
        }

        // General workspace configuration
        Element workspaces = getElement(root, WORKSPACES_ELEMENT);
        String workspaceDirectory = replaceVariables(
                getAttribute(workspaces, ROOT_PATH_ATTRIBUTE));

        String workspaceConfigDirectory =
                getAttribute(workspaces, CONFIG_ROOT_PATH_ATTRIBUTE, null);

        String defaultWorkspace = replaceVariables(
                getAttribute(workspaces, DEFAULT_WORKSPACE_ATTRIBUTE));

        int maxIdleTime = Integer.parseInt(
                getAttribute(workspaces, MAX_IDLE_TIME_ATTRIBUTE, "0"));

        // Workspace configuration template
        Element template = getElement(root, WORKSPACE_ELEMENT);

        // Versioning configuration
        VersioningConfig vc = parseVersioningConfig(root);

        // Optional search configuration
        SearchConfig sc = parseSearchConfig(root);

        return new RepositoryConfig(home, appName, amc, lmc, fsc,
                workspaceDirectory, workspaceConfigDirectory, defaultWorkspace,
                maxIdleTime, template, vc, sc, this);
    }

    /**
     * Parses workspace configuration. Workspace configuration uses the
     * following format:
     * <pre>
     *   &lt;Workspace name="..."&gt;
     *     &lt;FileSystem ...&gt;
     *     &lt;PersistenceManager ...&gt;
     *     &lt;SearchIndex ...&gt;
     *   &lt;/Workspace&gt;
     * </pre>
     * <p>
     * All the child elements (<code>FileSystem</code>,
     * <code>PersistenceManager</code>, and <code>SearchIndex</code>) are
     * {@link #parseBeanConfig(Element,String) bean configuration} elements.
     * In addition to bean configuration, the
     * {@link #parseSearchConfig(Element) search element} also contains
     * configuration for the search file system.
     * <p>
     * In addition to the configured information, the returned workspace
     * configuration object also contains the workspace home directory path
     * that is given as the ${wsp.home} parser variable. Note that the
     * variable <em>must</em> be available for the configuration document to
     * be correctly parsed.
     * <p>
     * Variable replacement is performed on the optional workspace name
     * attribute. If the name is not given, then the name of the workspace
     * home directory is used as the workspace name. Once the name has been
     * determined, it will be added as the ${wsp.name} variable in a temporary
     * configuration parser that is used to parse the contained configuration
     * elements.
     * <p>
     * The search index configuration element is optional. If it is not given,
     * then the workspace will not have search capabilities.
     * <p>
     * Note that the returned workspace configuration object has not been
     * initialized.
     *
     * @param xml workspace configuration document
     * @return workspace configuration
     * @throws ConfigurationException if the configuration is broken
     * @see #parseBeanConfig(Element, String)
     * @see #parseSearchConfig(Element)
     */
    public WorkspaceConfig parseWorkspaceConfig(InputSource xml)
            throws ConfigurationException {
        Element root = parseXML(xml);

        // Workspace home directory
        String home = variables.getProperty(WORKSPACE_HOME_VARIABLE);

        // Workspace name
        String name =
            getAttribute(root, NAME_ATTRIBUTE, new File(home).getName());

        // Create a temporary parser that contains the ${wsp.name} variable
        Properties tmpVariables = (Properties) variables.clone();
        tmpVariables.put(WORKSPACE_NAME_VARIABLE, name);
        ConfigurationParser tmpParser = createSubParser(tmpVariables);

        // File system implementation
        FileSystemConfig fsc = new FileSystemConfig(
                tmpParser.parseBeanConfig(root, FILE_SYSTEM_ELEMENT));

        // Persistence manager implementation
        PersistenceManagerConfig pmc = tmpParser.parsePersistenceManagerConfig(root);

        // Search implementation (optional)
        SearchConfig sc = tmpParser.parseSearchConfig(root);

        return new WorkspaceConfig(home, name, fsc, pmc, sc);
    }

    /**
     * Parses search index configuration. Search index configuration
     * uses the following format:
     * <pre>
     *   &lt;SearchIndex class="..."&gt;
     *     &lt;param name="..." value="..."&gt;
     *     ...
     *     &lt;FileSystem ...&gt;
     *   &lt;/Search&gt;
     * </pre>
     * <p/>
     * Both the <code>SearchIndex</code> and <code>FileSystem</code>
     * elements are {@link #parseBeanConfig(Element,String) bean configuration}
     * elements. If the search implementation class is not given, then
     * a default implementation is used.
     * <p/>
     * The search index is an optional feature of workspace configuration.
     * If the search configuration element is not found, then this method
     * returns <code>null</code>.
     * <p/>
     * The FileSystem element in a search index configuration is optional.
     * However some implementations may require a FileSystem.
     *
     * @param parent parent of the <code>SearchIndex</code> element
     * @return search configuration, or <code>null</code>
     * @throws ConfigurationException if the configuration is broken
     */
    protected SearchConfig parseSearchConfig(Element parent)
            throws ConfigurationException {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && SEARCH_INDEX_ELEMENT.equals(child.getNodeName())) {
                Element element = (Element) child;

                // Search implementation class
                String className = getAttribute(
                        element, CLASS_ATTRIBUTE, DEFAULT_QUERY_HANDLER);

                // Search parameters
                Properties parameters = parseParameters(element);

                // Optional file system implementation
                FileSystemConfig fsc = null;
                if (getElement(element, FILE_SYSTEM_ELEMENT, false) != null) {
                    fsc = new FileSystemConfig(
                            parseBeanConfig(element, FILE_SYSTEM_ELEMENT));
                }

                return new SearchConfig(className, parameters, fsc);
            }
        }
        return null;
    }

    /**
     * Parses versioning configuration. Versioning configuration uses the
     * following format:
     * <pre>
     *   &lt;Versioning rootPath="..."&gt;
     *     &lt;FileSystem ...&gt;
     *     &lt;PersistenceManager ...&gt;
     *   &lt;/Versioning&gt;
     * </pre>
     * <p>
     * Both the <code>FileSystem</code> and <code>PersistenceManager</code>
     * elements are {@link #parseBeanConfig(Element,String) bean configuration}
     * elements. In addition to the bean parameter values,
     * {@link #replaceVariables(String) variable replacement} is performed
     * also on the versioning root path attribute.
     *
     * @param parent parent of the <code>Versioning</code> element
     * @return versioning configuration
     * @throws ConfigurationException if the configuration is broken
     */
    protected VersioningConfig parseVersioningConfig(Element parent)
            throws ConfigurationException {
        Element element = getElement(parent, VERSIONING_ELEMENT);

        // Versioning home directory
        String home =
            replaceVariables(getAttribute(element, ROOT_PATH_ATTRIBUTE));

        // File system implementation
        FileSystemConfig fsc = new FileSystemConfig(
                parseBeanConfig(element, FILE_SYSTEM_ELEMENT));

        // Persistence manager implementation
        PersistenceManagerConfig pmc = parsePersistenceManagerConfig(element);

        return new VersioningConfig(home, fsc, pmc);
    }

    /**
     * Parses the PersistenceManager config.
     *
     * @param parent
     * @return
     * @throws ConfigurationException
     */
    protected PersistenceManagerConfig parsePersistenceManagerConfig(Element parent)
            throws ConfigurationException {

        return new PersistenceManagerConfig(
                parseBeanConfig(parent, PERSISTENCE_MANAGER_ELEMENT));
    }

    /**
     * Parses a named bean configuration from the given element.
     * Bean configuration uses the following format:
     * <pre>
     *   &lt;BeanName class="..."&gt;
     *     &lt;param name="..." value="..."/&gt;
     *     ...
     *   &lt;/BeanName&gt;
     * </pre>
     * <p>
     * The returned bean configuration object contains the configured
     * class name and configuration parameters. Variable replacement
     * is performed on the parameter values.
     *
     * @param parent parent element
     * @param name name of the bean configuration element
     * @return bean configuration,
     * @throws ConfigurationException if the configuration element does not
     *                                exist or is broken
     */
    protected BeanConfig parseBeanConfig(Element parent, String name)
            throws ConfigurationException {
        // Bean configuration element
        Element element = getElement(parent, name);

        // Bean implementation class
        String className = getAttribute(element, CLASS_ATTRIBUTE);

        // Bean properties
        Properties properties = parseParameters(element);

        return new BeanConfig(className, properties);
    }

    /**
     * Parses the configuration parameters of the given element.
     * Parameters are stored as
     * <code>&lt;param name="..." value="..."/&gt;</code>
     * child elements. This method parses all param elements,
     * performs {@link #replaceVariables(String) variable replacement}
     * on parameter values, and returns the resulting name-value pairs.
     *
     * @param element configuration element
     * @return configuration parameters
     * @throws ConfigurationException if a <code>param</code> element does
     *                                not contain the <code>name</code> and
     *                                <code>value</code> attributes
     */
    protected Properties parseParameters(Element element)
            throws ConfigurationException {
        Properties parameters = new Properties();

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && PARAM_ELEMENT.equals(child.getNodeName())) {
                Element parameter = (Element) child;
                Attr name = parameter.getAttributeNode(NAME_ATTRIBUTE);
                if (name == null) {
                    throw new ConfigurationException("Parameter name not set");
                }
                Attr value = parameter.getAttributeNode(VALUE_ATTRIBUTE);
                if (value == null) {
                    throw new ConfigurationException("Parameter value not set");
                }
                parameters.put(
                        name.getValue(), replaceVariables(value.getValue()));
            }
        }

        return parameters;
    }

    /**
     * Performs variable replacement on the given string value.
     * Each <code>${...}</code> sequence within the given value is replaced
     * with the value of the named parser variable. The replacement is not
     * done if the named variable does not exist.
     *
     * @param value original value
     * @return value after variable replacements
     * @throws ConfigurationException if the replacement of a referenced
     *                                variable is not found
     */
    protected String replaceVariables(String value)
            throws ConfigurationException {
        StringBuffer result = new StringBuffer();

        // Value:
        // +--+-+--------+-+-----------------+
        // |  |p|-->     |q|-->              |
        // +--+-+--------+-+-----------------+
        int p = 0, q = value.indexOf("${");                // Find first ${
        while (q != -1) {
            result.append(value.substring(p, q));          // Text before ${
            p = q;
            q = value.indexOf("}", q + 2);                 // Find }
            if (q != -1) {
                String variable = value.substring(p + 2, q);
                String replacement = variables.getProperty(variable);
                if (replacement == null) {
                    throw new ConfigurationException(
                            "Replacement not found for ${" + variable + "}.");
                }
                result.append(replacement);
                p = q + 1;
                q = value.indexOf("${", p);                // Find next ${
            }
        }
        result.append(value.substring(p, value.length())); // Trailing text

        return result.toString();
    }

    /**
     * Parses the given XML document and returns the DOM root element.
     * A custom entity resolver is used to make the included configuration
     * file DTD available using the specified public identifiers.
     *
     * @see ConfigurationEntityResolver
     * @param xml xml document
     * @return root element
     * @throws ConfigurationException if the configuration document could
     *                                not be read or parsed
     */
    protected Element parseXML(InputSource xml) throws ConfigurationException {
        try {
            DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new ConfigurationEntityResolver());
            Document document = builder.parse(xml);
            return document.getDocumentElement();
        } catch (ParserConfigurationException e) {
            throw new ConfigurationException(
                    "Unable to create configuration XML parser", e);
        } catch (SAXException e) {
            throw new ConfigurationException(
                    "Configuration file syntax error.", e);
        } catch (IOException e) {
            throw new ConfigurationException(
                    "Configuration file could not be read.", e);
        }
    }

    /**
     * Returns the named child of the given parent element.
     *
     * @param parent parent element
     * @param name name of the child element
     * @return named child element
     * @throws ConfigurationException
     * @throws ConfigurationException if the child element is not found
     */
    protected Element getElement(Element parent, String name) throws ConfigurationException {
        return getElement(parent, name, true);
    }

    /**
     * Returns the named child of the given parent element.
     *
     * @param parent parent element
     * @param name name of the child element
     * @param required indicates if the child element is required
     * @return named child element, or <code>null</code> if not found and
     *         <code>required</code> is <code>false</code>.
     * @throws ConfigurationException if the child element is not found and
     *         <code>required</code> is <code>true</code>.
     */
    protected Element getElement(Element parent, String name, boolean required)
            throws ConfigurationException {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        if (required) {
            throw new ConfigurationException(
                    "Configuration element " + name + " not found in "
                    + parent.getNodeName() + ".");
        } else {
            return null;
        }
    }

    /**
     * Returns the value of the named attribute of the given element.
     *
     * @param element element
     * @param name attribute name
     * @return attribute value
     * @throws ConfigurationException if the attribute is not found
     */
    protected String getAttribute(Element element, String name)
            throws ConfigurationException {
        Attr attribute = element.getAttributeNode(name);
        if (attribute != null) {
            return attribute.getValue();
        } else {
            throw new ConfigurationException(
                    "Configuration attribute " + name + " not found in "
                    + element.getNodeName() + ".");
        }
    }

    /**
     * Returns the value of the named attribute of the given element.
     * If the attribute is not found, then the given default value is returned.
     *
     * @param element element
     * @param name attribute name
     * @param def default value
     * @return attribute value, or the default value
     */
    protected String getAttribute(Element element, String name, String def) {
        Attr attribute = element.getAttributeNode(name);
        if (attribute != null) {
            return attribute.getValue();
        } else {
            return def;
        }
    }

    /**
     * Creates a new instance of a configuration parser but with overlayed
     * variables.
     *
     * @param variables the variables overlay
     * @return a new configuration parser instance
     */
    protected ConfigurationParser createSubParser(Properties variables) {
        // overlay the properties
        Properties props = new Properties(this.variables);
        props.putAll(variables);
        return new ConfigurationParser(props);
    }
}
