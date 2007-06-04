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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.value.BLOBFileValue;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.extractor.TextExtractor;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.name.PathFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;

import javax.jcr.NamespaceException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;

import java.io.InputStream;
import java.io.Reader;
import java.io.IOException;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Set;

/**
 * Creates a lucene <code>Document</code> object from a {@link javax.jcr.Node}.
 */
public class NodeIndexer {

    /**
     * The logger instance for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(NodeIndexer.class);

    /**
     * The <code>NodeState</code> of the node to index
     */
    protected final NodeState node;

    /**
     * The persistent item state provider
     */
    protected final ItemStateManager stateProvider;

    /**
     * Namespace mappings to use for indexing. This is the internal
     * namespace mapping.
     */
    protected final NamespaceMappings mappings;

    /**
     * Content extractor.
     */
    protected final TextExtractor extractor;

    /**
     * If set to <code>true</code> the fulltext field is stored and and a term
     * vector is created with offset information.
     */
    protected boolean supportHighlighting = false;

    /**
     * Creates a new node indexer.
     *
     * @param node          the node state to index.
     * @param stateProvider the persistent item state manager to retrieve properties.
     * @param mappings      internal namespace mappings.
     * @param extractor     content extractor
     */
    public NodeIndexer(NodeState node,
                          ItemStateManager stateProvider,
                          NamespaceMappings mappings,
                          TextExtractor extractor) {
        this.node = node;
        this.stateProvider = stateProvider;
        this.mappings = mappings;
        this.extractor = extractor;
    }

    /**
     * Returns the <code>NodeId</code> of the indexed node.
     * @return the <code>NodeId</code> of the indexed node.
     */
    public NodeId getNodeId() {
        return node.getNodeId();
    }

    /**
     * If set to <code>true</code> additional information is stored in the index
     * to support highlighting using the rep:excerpt pseudo property.
     *
     * @param b <code>true</code> to enable highlighting support.
     */
    public void setSupportHighlighting(boolean b) {
        supportHighlighting = b;
    }

    /**
     * Creates a lucene Document.
     *
     * @return the lucene Document with the index layout.
     * @throws RepositoryException if an error occurs while reading property
     *                             values from the <code>ItemStateProvider</code>.
     */
    protected Document createDoc() throws RepositoryException {
        Document doc = new Document();

        // special fields
        // UUID
        doc.add(new Field(FieldNames.UUID, node.getNodeId().getUUID().toString(), Field.Store.YES, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
        try {
            // parent UUID
            if (node.getParentId() == null) {
                // root node
                doc.add(new Field(FieldNames.PARENT, "", Field.Store.YES, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
                doc.add(new Field(FieldNames.LABEL, "", Field.Store.NO, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
            } else {
                doc.add(new Field(FieldNames.PARENT, node.getParentId().toString(), Field.Store.YES, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
                NodeState parent = (NodeState) stateProvider.getItemState(node.getParentId());
                NodeState.ChildNodeEntry child = parent.getChildNodeEntry(node.getNodeId());
                if (child == null) {
                    // this can only happen when jackrabbit
                    // is running in a cluster.
                    throw new RepositoryException("Missing child node entry " +
                            "for node with id: " + node.getNodeId());
                }
                String name = NameFormat.format(child.getName(), mappings);
                doc.add(new Field(FieldNames.LABEL, name, Field.Store.NO, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
            }
        } catch (NoSuchItemStateException e) {
            throwRepositoryException(e);
        } catch (ItemStateException e) {
            throwRepositoryException(e);
        } catch (NoPrefixDeclaredException e) {
            // will never happen, because this.mappings will dynamically add
            // unknown uri<->prefix mappings
        }

        Set props = node.getPropertyNames();
        for (Iterator it = props.iterator(); it.hasNext();) {
            QName propName = (QName) it.next();
            PropertyId id = new PropertyId(node.getNodeId(), propName);
            try {
                PropertyState propState = (PropertyState) stateProvider.getItemState(id);
                InternalValue[] values = propState.getValues();
                for (int i = 0; i < values.length; i++) {
                    addValue(doc, values[i], propState.getName());
                }
                if (values.length > 1) {
                    // real multi-valued
                    addMVPName(doc, propState.getName());
                }
            } catch (NoSuchItemStateException e) {
                throwRepositoryException(e);
            } catch (ItemStateException e) {
                throwRepositoryException(e);
            }
        }
        return doc;
    }

    /**
     * Wraps the exception <code>e</code> into a <code>RepositoryException</code>
     * and throws the created exception.
     *
     * @param e the base exception.
     */
    private void throwRepositoryException(Exception e)
            throws RepositoryException {
        String msg = "Error while indexing node: " + node.getNodeId() + " of "
            + "type: " + node.getNodeTypeName();
        throw new RepositoryException(msg, e);
    }

    /**
     * Adds a {@link FieldNames#MVP} field to <code>doc</code> with the resolved
     * <code>name</code> using the internal search index namespace mapping.
     *
     * @param doc  the lucene document.
     * @param name the name of the multi-value property.
     */
    private void addMVPName(Document doc, QName name) {
        try {
            String propName = NameFormat.format(name, mappings);
            doc.add(new Field(FieldNames.MVP, propName, Field.Store.NO, Field.Index.UN_TOKENIZED, Field.TermVector.NO));
        } catch (NoPrefixDeclaredException e) {
            // will never happen, prefixes are created dynamically
        }
    }

    /**
     * Adds a value to the lucene Document.
     *
     * @param doc   the document.
     * @param value the internal jackrabbit value.
     * @param name  the name of the property.
     */
    private void addValue(Document doc, InternalValue value, QName name) {
        String fieldName = name.getLocalName();
        try {
            fieldName = NameFormat.format(name, mappings);
        } catch (NoPrefixDeclaredException e) {
            // will never happen
        }
        Object internalValue = value.internalValue();
        switch (value.getType()) {
            case PropertyType.BINARY:
                addBinaryValue(doc, fieldName, internalValue);
                break;
            case PropertyType.BOOLEAN:
                addBooleanValue(doc, fieldName, internalValue);
                break;
            case PropertyType.DATE:
                addCalendarValue(doc, fieldName, internalValue);
                break;
            case PropertyType.DOUBLE:
                addDoubleValue(doc, fieldName, internalValue);
                break;
            case PropertyType.LONG:
                addLongValue(doc, fieldName, internalValue);
                break;
            case PropertyType.REFERENCE:
                addReferenceValue(doc, fieldName, internalValue);
                break;
            case PropertyType.PATH:
                addPathValue(doc, fieldName, internalValue);
                break;
            case PropertyType.STRING:
                // do not fulltext index jcr:uuid String
                boolean tokenize = !name.equals(QName.JCR_UUID);
                addStringValue(doc, fieldName, internalValue, tokenize);
                break;
            case PropertyType.NAME:
                addNameValue(doc, fieldName, internalValue);
                break;
            default:
                throw new IllegalArgumentException("illegal internal value type");
        }
    }

    /**
     * Adds the binary value to the document as the named field.
     * <p/>
     * This implementation checks if this {@link #node} is of type nt:resource
     * and if that is the case, tries to extract text from the binary property
     * using the {@link #extractor}.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addBinaryValue(Document doc,
                                  String fieldName,
                                  Object internalValue) {
        // 'check' if node is of type nt:resource
        try {
            String jcrData = mappings.getPrefix(QName.NS_JCR_URI) + ":data";
            if (!jcrData.equals(fieldName)) {
                // don't know how to index
                return;
            }

            InternalValue typeValue = getValue(QName.JCR_MIMETYPE);
            if (typeValue != null) {
                String type = typeValue.internalValue().toString();

                // jcr:encoding is not mandatory
                String encoding = null;
                InternalValue encodingValue = getValue(QName.JCR_ENCODING);
                if (encodingValue != null) {
                    encoding = encodingValue.internalValue().toString();
                }

                InputStream stream =
                        ((BLOBFileValue) internalValue).getStream();
                Reader reader = extractor.extractText(stream, type, encoding);
                doc.add(createFulltextField(reader));
            }
        } catch (Exception e) {
            // TODO: How to recover from a transient indexing failure?
            log.warn("Exception while indexing binary property: " + e.toString());
            log.debug("Dump: ", e);
        }
    }

    /**
     * Utility method that extracts the first value of the named property
     * of the current node. Returns <code>null</code> if the property does
     * not exist or contains no values.
     *
     * @param name property name
     * @return value of the named property, or <code>null</code>
     * @throws ItemStateException if the property can not be accessed
     */
    protected InternalValue getValue(QName name) throws ItemStateException {
        try {
            PropertyId id = new PropertyId(node.getNodeId(), name);
            PropertyState property =
                (PropertyState) stateProvider.getItemState(id);
            InternalValue[] values = property.getValues();
            if (values.length > 0) {
                return values[0];
            } else {
                return null;
            }
        } catch (NoSuchItemStateException e) {
            return null;
        }
    }

    /**
     * Adds the string representation of the boolean value to the document as
     * the named field.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addBooleanValue(Document doc, String fieldName, Object internalValue) {
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, internalValue.toString()),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the calendar value to the document as the named field. The calendar
     * value is converted to an indexable string value using the {@link DateField}
     * class.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addCalendarValue(Document doc, String fieldName, Object internalValue) {
        long millis = ((Calendar) internalValue).getTimeInMillis();
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, DateField.timeToString(millis)),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the double value to the document as the named field. The double
     * value is converted to an indexable string value using the
     * {@link DoubleField} class.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addDoubleValue(Document doc, String fieldName, Object internalValue) {
        double doubleVal = ((Double) internalValue).doubleValue();
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, DoubleField.doubleToString(doubleVal)),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the long value to the document as the named field. The long
     * value is converted to an indexable string value using the {@link LongField}
     * class.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addLongValue(Document doc, String fieldName, Object internalValue) {
        long longVal = ((Long) internalValue).longValue();
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, LongField.longToString(longVal)),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the reference value to the document as the named field. The value's
     * string representation is added as the reference data. Additionally the
     * reference data is stored in the index.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addReferenceValue(Document doc, String fieldName, Object internalValue) {
        String uuid = internalValue.toString();
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, uuid),
                Field.Store.YES, // store
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the path value to the document as the named field. The path
     * value is converted to an indexable string value using the name space
     * mappings with which this class has been created.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addPathValue(Document doc, String fieldName, Object internalValue) {
        Path path = (Path) internalValue;
        String pathString = path.toString();
        try {
            pathString = PathFormat.format(path, mappings);
        } catch (NoPrefixDeclaredException e) {
            // will never happen
        }
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, pathString),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Adds the string value to the document both as the named field and for
     * full text indexing.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     * @deprecated Use {@link #addStringValue(Document, String, Object, boolean)
     *             addStringValue(Document, String, Object, boolean)} instead.
     */
    protected void addStringValue(Document doc, String fieldName, Object internalValue) {
        addStringValue(doc, fieldName, internalValue, true);
    }

    /**
     * Adds the string value to the document both as the named field and
     * optionally for full text indexing if <code>tokenized</code> is
     * <code>true</code>.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     * @param tokenized     If <code>true</code> the string is also tokenized
     *                      and fulltext indexed.
     */
    protected void addStringValue(Document doc, String fieldName, Object internalValue, boolean tokenized) {
        String stringValue = String.valueOf(internalValue);

        // simple String
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, stringValue),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
        if (tokenized) {
            // also create fulltext index of this value
            doc.add(createFulltextField(stringValue));
            // create fulltext index on property
            int idx = fieldName.indexOf(':');
            fieldName = fieldName.substring(0, idx + 1)
                    + FieldNames.FULLTEXT_PREFIX + fieldName.substring(idx + 1);
            doc.add(new Field(fieldName, stringValue,
                    Field.Store.NO,
                    Field.Index.TOKENIZED,
                    Field.TermVector.NO));
        }
    }

    /**
     * Adds the name value to the document as the named field. The name
     * value is converted to an indexable string treating the internal value
     * as a qualified name and mapping the name space using the name space
     * mappings with which this class has been created.
     *
     * @param doc           The document to which to add the field
     * @param fieldName     The name of the field to add
     * @param internalValue The value for the field to add to the document.
     */
    protected void addNameValue(Document doc, String fieldName, Object internalValue) {
        QName qualiName = (QName) internalValue;
        String normValue = internalValue.toString();
        try {
            normValue = mappings.getPrefix(qualiName.getNamespaceURI())
                    + ":" + qualiName.getLocalName();
        } catch (NamespaceException e) {
            // will never happen
        }
        doc.add(new Field(FieldNames.PROPERTIES,
                FieldNames.createNamedValue(fieldName, normValue),
                Field.Store.NO,
                Field.Index.UN_TOKENIZED,
                Field.TermVector.NO));
    }

    /**
     * Creates a fulltext field for the string <code>value</code>.
     *
     * @param value the string value.
     * @return a lucene field.
     */
    protected Field createFulltextField(String value) {
        if (supportHighlighting) {
            // store field compressed if greater than 16k
            Field.Store stored;
            if (value.length() > 0x4000) {
                stored = Field.Store.COMPRESS;
            } else {
                stored = Field.Store.YES;
            }
            return new Field(FieldNames.FULLTEXT, value, stored,
                    Field.Index.TOKENIZED, Field.TermVector.WITH_OFFSETS);
        } else {
            return new Field(FieldNames.FULLTEXT, value,
                    Field.Store.NO, Field.Index.TOKENIZED);
        }
    }

    /**
     * Creates a fulltext field for the reader <code>value</code>.
     *
     * @param value the reader value.
     * @return a lucene field.
     */
    protected Field createFulltextField(Reader value) {
        if (supportHighlighting) {
            // need to create a string value
            StringBuffer textExtract = new StringBuffer();
            char[] buffer = new char[1024];
            int len;
            try {
                while ((len = value.read(buffer)) > -1) {
                    textExtract.append(buffer, 0, len);
                }
            } catch (IOException e) {
                log.warn("Exception reading value for fulltext field: " +
                        e.getMessage());
                log.debug("Dump:", e);
            } finally {
                try {
                    value.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            return createFulltextField(textExtract.toString());
        } else {
            return new Field(FieldNames.FULLTEXT, value);
        }
    }
}
