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
package org.apache.jackrabbit.core.xml;

import org.apache.jackrabbit.core.NoPrefixDeclaredException;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.PropertyImpl;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.util.ValueHelper;
import org.apache.log4j.Logger;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.io.IOException;
import java.io.Writer;

/**
 * A <code>SysViewSAXEventGenerator</code> instance can be used to generate SAX events
 * representing the serialized form of an item in System View XML.
 */
public class SysViewSAXEventGenerator extends AbstractSAXEventGenerator {

    private static Logger log = Logger.getLogger(SysViewSAXEventGenerator.class);

    /**
     * The XML elements and attributes used in serialization
     */
    public static final String NODE_ELEMENT = "node";
    public static final String PREFIXED_NODE_ELEMENT =
            NS_SV_PREFIX + ":" + NODE_ELEMENT;

    public static final String PROPERTY_ELEMENT = "property";
    public static final String PREFIXED_PROPERTY_ELEMENT =
            NS_SV_PREFIX + ":" + PROPERTY_ELEMENT;;

    public static final String VALUE_ELEMENT = "value";
    public static final String PREFIXED_VALUE_ELEMENT =
            NS_SV_PREFIX + ":" + VALUE_ELEMENT;;

    public static final String NAME_ATTRIBUTE = "name";
    public static final String PREFIXED_NAME_ATTRIBUTE =
            NS_SV_PREFIX + ":" + NAME_ATTRIBUTE;

    public static final String TYPE_ATTRIBUTE = "type";
    public static final String PREFIXED_TYPE_ATTRIBUTE =
            NS_SV_PREFIX + ":" + TYPE_ATTRIBUTE;

    public static final String CDATA_TYPE = "CDATA";
    public static final String ENUMERATION_TYPE = "ENUMERATION";

    /**
     * Constructor
     *
     * @param node           the node state which should be serialized
     * @param noRecurse      if true, only <code>node</code> and its properties will
     *                       be serialized; otherwise the entire hierarchy starting with
     *                       <code>node</code> will be serialized.
     * @param skipBinary     flag governing whether binary properties are to be serialized.
     * @param session        the session to be used for resolving namespace mappings
     * @param contentHandler the content handler to feed the SAX events to
     */
    public SysViewSAXEventGenerator(NodeImpl node, boolean noRecurse,
                                    boolean skipBinary,
                                    SessionImpl session,
                                    ContentHandler contentHandler) {
        super(node, noRecurse, skipBinary, session, contentHandler);
    }

    /**
     * {@inheritDoc}
     */
    protected void entering(NodeImpl node, int level)
            throws RepositoryException, SAXException {
        QName name = node.getQName();

        AttributesImpl attrs = new AttributesImpl();
        // name attribute
        String nodeName;
        try {
            if (node.getDepth() == 0) {
                // root node needs a name
                nodeName = JCR_ROOT.toJCRName(session.getNamespaceResolver());
            } else {
                nodeName = name.toJCRName(session.getNamespaceResolver());
            }
        } catch (NoPrefixDeclaredException npde) {
            // should never get here...
            String msg = "internal error: encountered unregistered namespace";
            log.debug(msg);
            throw new RepositoryException(msg, npde);
        }

        attrs.addAttribute(NS_SV_URI, NAME_ATTRIBUTE, PREFIXED_NAME_ATTRIBUTE,
                CDATA_TYPE, nodeName);
        // start node element
        contentHandler.startElement(NS_SV_URI, NODE_ELEMENT,
                PREFIXED_NODE_ELEMENT, attrs);
    }

    /**
     * {@inheritDoc}
     */
    protected void enteringProperties(NodeImpl node, int level)
            throws RepositoryException, SAXException {
        // nop
    }

    /**
     * {@inheritDoc}
     */
    protected void leavingProperties(NodeImpl node, int level)
            throws RepositoryException, SAXException {
        // nop
    }

    /**
     * {@inheritDoc}
     */
    protected void leaving(NodeImpl node, int level)
            throws RepositoryException, SAXException {
        // end node element
        contentHandler.endElement(NS_SV_URI, NODE_ELEMENT, PREFIXED_NODE_ELEMENT);
    }

    /**
     * {@inheritDoc}
     */
    protected void entering(PropertyImpl prop, int level)
            throws RepositoryException, SAXException {
        if (prop.getType() == PropertyType.BINARY && skipBinary) {
            return;
        }

        QName name = prop.getQName();
        String propName;
        try {
            propName = name.toJCRName(session.getNamespaceResolver());
        } catch (NoPrefixDeclaredException npde) {
            // should never get here...
            String msg = "internal error: encountered unregistered namespace";
            log.debug(msg);
            throw new RepositoryException(msg, npde);
        }
        AttributesImpl attrs = new AttributesImpl();
        // name attribute
        attrs.addAttribute(NS_SV_URI, NAME_ATTRIBUTE, PREFIXED_NAME_ATTRIBUTE,
                CDATA_TYPE, propName);
        // type attribute
        int type = prop.getType();
        String typeName;
        try {
            typeName = PropertyType.nameFromValue(type);
        } catch (IllegalArgumentException iae) {
            // should never be getting here
            throw new RepositoryException("unexpected property-type ordinal: " + type, iae);
        }
        attrs.addAttribute(NS_SV_URI, TYPE_ATTRIBUTE, PREFIXED_TYPE_ATTRIBUTE,
                ENUMERATION_TYPE, typeName);

        // start property element
        contentHandler.startElement(NS_SV_URI, PROPERTY_ELEMENT,
                PREFIXED_PROPERTY_ELEMENT, attrs);

        // values
        boolean multiValued = prop.getDefinition().isMultiple();
        Value[] vals;
        if (multiValued) {
            vals = prop.getValues();
        } else {
            vals = new Value[]{prop.getValue()};
        }
        for (int i = 0; i < vals.length; i++) {
            Value val = vals[i];

            // start value element
            contentHandler.startElement(NS_SV_URI, VALUE_ELEMENT,
                    PREFIXED_VALUE_ELEMENT, new AttributesImpl());

            // characters
            Writer writer = new Writer() {
                public void close() /*throws IOException*/ {
                }

                public void flush() /*throws IOException*/ {
                }

                public void write(char[] cbuf, int off, int len) throws IOException {
                    try {
                        contentHandler.characters(cbuf, off, len);
                    } catch (SAXException se) {
                        throw new IOException(se.toString());
                    }
                }
            };
            try {
                ValueHelper.serialize(val, false, writer);
                // no need to close our Writer implementation
                //writer.close();
            } catch (IOException ioe) {
                // check if the exception wraps a SAXException
                // (see Writer.write(char[], int, int) above)
                Throwable t = ioe.getCause();
                if (t != null && t instanceof SAXException) {
                    throw (SAXException) t;
                } else {
                    throw new SAXException(ioe);
                }
            }

            // end value element
            contentHandler.endElement(NS_SV_URI, VALUE_ELEMENT,
                    PREFIXED_VALUE_ELEMENT);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void leaving(PropertyImpl prop, int level)
            throws RepositoryException, SAXException {
        if (prop.getType() == PropertyType.BINARY && skipBinary) {
            return;
        }
        contentHandler.endElement(NS_SV_URI, PROPERTY_ELEMENT,
                PREFIXED_PROPERTY_ELEMENT);
    }
}
