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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.NamespaceResolver;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.PropertyImpl;
import org.apache.jackrabbit.core.IllegalNameException;
import org.apache.jackrabbit.core.UnknownPrefixException;
import org.apache.jackrabbit.core.query.QueryConstants;

import javax.jcr.query.RowIterator;
import javax.jcr.query.Row;
import javax.jcr.Value;
import javax.jcr.RepositoryException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathValue;
import javax.jcr.LongValue;
import javax.jcr.PropertyType;
import javax.jcr.StringValue;
import javax.jcr.Property;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.HashSet;

/**
 * Implements the {@link javax.jcr.query.RowIterator} interface returned by
 * a {@link javax.jcr.query.QueryResult}.
 */
class RowIteratorImpl implements RowIterator {

    /** Iterator over nodes, that constitute the result set. */
    private final NodeIteratorImpl nodes;

    /** Array of select property names */
    private final QName[] properties;

    /** The <code>NamespaceResolver</code> of the user <code>Session</code>. */
    private final NamespaceResolver resolver;

    /**
     * Creates a new <code>RowIteratorImpl</code> that iterates over the result
     * nodes.
     * @param nodes a <code>NodeIteratorImpl</code> that contains the nodes of
     * the query result.
     * @param properties <code>QName</code> of the select properties.
     * @param resolver <code>NamespaceResolver</code> of the user
     *   <code>Session</code>.
     */
    RowIteratorImpl(NodeIteratorImpl nodes, QName[] properties, NamespaceResolver resolver) {
        this.nodes = nodes;
        this.properties = properties;
        this.resolver = resolver;
    }

    /**
     * Returns the next <code>Row</code> in the iteration.
     *
     * @return the next <code>Row</code> in the iteration.
     * @throws NoSuchElementException if iteration has no more
     *                                <code>Row</code>s.
    */
    public Row nextRow() throws NoSuchElementException {
        return new RowImpl(nodes.getScore(), nodes.nextNodeImpl());
    }

    /**
     * Skip a number of <code>Row</code>s in this iterator.
     *
     * @param skipNum the non-negative number of <code>Row</code>s to skip
     * @throws NoSuchElementException if skipped past the last
     *                                <code>Row</code> in this iterator.
     */
    public void skip(long skipNum) throws NoSuchElementException {
        nodes.skip(skipNum);
    }

    /**
     * Returns the number of <code>Row</code>s in this iterator.
     * @return the number of <code>Row</code>s in this iterator.
     */
    public long getSize() {
        return nodes.getSize();
    }

    /**
     * Returns the current position within this iterator. The number
     * returned is the 0-based index of the next <code>Row</code> in the iterator,
     * i.e. the one that will be returned on the subsequent <code>next</code> call.
     * <p/>
     * Note that this method does not check if there is a next element,
     * i.e. an empty iterator will always return 0.
     *
     * @return the current position withing this iterator.
     */
    public long getPosition() {
        return nodes.getPosition();
    }

    /**
     * @throws UnsupportedOperationException always.
     */
    public void remove() {
        throw new UnsupportedOperationException("remove");
    }

    /**
     * Returns <code>true</code> if the iteration has more <code>Row</code>s.
     * (In other words, returns <code>true</code> if <code>next</code> would
     * return an <code>Row</code> rather than throwing an exception.)
     *
     * @return <code>true</code> if the iterator has more elements.
     */
    public boolean hasNext() {
        return nodes.hasNext();
    }

    /**
     * Returns the next <code>Row</code> in the iteration.
     *
     * @return the next <code>Row</code> in the iteration.
     * @throws NoSuchElementException if iteration has no more <code>Row</code>s.
    */
    public Object next() throws NoSuchElementException {
        return nextRow();
    }

    //---------------------< class RowImpl >------------------------------------

    /**
     * Implements the {@link javax.jcr.query.Row} interface, which represents
     * a row in the query result.
     */
    class RowImpl implements Row {

        /** The score for this result row */
        private final float score;

        /** The underlying <code>Node</code> of this result row. */
        private final NodeImpl node;

        /** Cached value array for returned by {@link #getValues()}. */
        private Value[] values;

        /** Set of select property <code>QName</code>s. */
        private Set propertySet;

        /**
         * Creates a new <code>RowImpl</code> instance based on <code>node</code>.
         * @param score the score value for this result row
         * @param node the underlying <code>Node</code> for this <code>Row</code>.
         */
        RowImpl(float score, NodeImpl node) {
            this.score = score;
            this.node = node;
        }

        /**
         * Returns an array of all the values in the same order as the property
         * names (column names) returned by
         * {@link javax.jcr.query.QueryResult#getColumnNames()}.
         *
         * @return a <code>Value</code> array.
         * @throws RepositoryException if an error occurs while retrieving the
         *  values from the <code>Node</code>.
         */
        public Value[] getValues() throws RepositoryException {
            if (values == null) {
                Value[] tmp = new Value[properties.length];
                for (int i = 0; i < properties.length; i++) {
                    if (node.hasProperty(properties[i])) {
                        PropertyImpl prop = node.getProperty(properties[i]);
                        if (!prop.getDefinition().isMultiple()) {
                            if (prop.getDefinition().getRequiredType() == PropertyType.UNDEFINED) {
                                tmp[i] = new StringValue(prop.getString());
                            } else {
                                tmp[i] = prop.getValue();
                            }
                        } else {
                            // mvp values cannot be returned
                            tmp[i] = null;
                        }
                    } else {
                        // property not set or jcr:path / jcr:score
                        if (QueryConstants.JCR_PATH.equals(properties[i])) {
                            tmp[i] = PathValue.valueOf(node.getPath());
                        } else if (QueryConstants.JCR_SCORE.equals(properties[i])) {
                            tmp[i] = new LongValue((int) (score * 1000f));
                        } else {
                            tmp[i] = null;
                        }
                    }
                }
                values = tmp;
            }
            // return a copy of the array
            Value[] ret = new Value[values.length];
            System.arraycopy(values, 0, ret, 0, values.length);
            return ret;
        }

        /**
         * Returns the value of the indicated  property in this <code>Row</code>.
         * <p/>
         * If <code>propertyName</code> is not among the column names of the
         * query result table, an <code>ItemNotFoundException</code> is thrown.
         *
         * @return a <code>Value</code>
         * @throws ItemNotFoundException if <code>propertyName</code> is not
         * among the column names of the query result table.
         * @throws RepositoryException if <code>propertyName</code> is not a
         *  valid property name.
         */
        public Value getValue(String propertyName) throws ItemNotFoundException, RepositoryException {
            if (propertySet == null) {
                // create the set first
                Set tmp = new HashSet();
                tmp.addAll(Arrays.asList(properties));
                propertySet = tmp;
            }
            try {
                QName prop = QName.fromJCRName(propertyName, resolver);
                if (!propertySet.contains(prop)) {
                    throw new ItemNotFoundException(propertyName);
                }
                if (node.hasProperty(prop)) {
                    Property p = node.getProperty(prop);
                    if (p.getDefinition().getRequiredType() == PropertyType.UNDEFINED) {
                        return new StringValue(p.getString());
                    } else {
                        return p.getValue();
                    }
                } else {
                    // either jcr:score, jcr:path or not set
                    if (QueryConstants.JCR_PATH.equals(prop)) {
                        return PathValue.valueOf(node.getPath());
                    } else if (QueryConstants.JCR_SCORE.equals(prop)) {
                        return new LongValue((int) (score * 1000f));
                    } else {
                        return null;
                    }
                }
            } catch (IllegalNameException e) {
                throw new RepositoryException(e.getMessage(), e);
            } catch (UnknownPrefixException e) {
                throw new RepositoryException(e.getMessage(), e);
            }
        }
    }
}
