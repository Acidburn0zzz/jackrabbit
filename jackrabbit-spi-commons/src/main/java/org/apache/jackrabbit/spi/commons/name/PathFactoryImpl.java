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
package org.apache.jackrabbit.spi.commons.name;

import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.PathFactory;
import org.apache.jackrabbit.spi.NameFactory;

import javax.jcr.RepositoryException;
import javax.jcr.PathNotFoundException;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * <code>PathFactoryImpl</code>...
 */
public class PathFactoryImpl implements PathFactory {

    private static PathFactory FACTORY = new PathFactoryImpl();

    private static final String CURRENT_LITERAL = ".";
    private static final String PARENT_LITERAL = "..";

    private static final NameFactory NAME_FACTORY = NameFactoryImpl.getInstance();
    private final static Name CURRENT_NAME = NAME_FACTORY.create(Name.NS_DEFAULT_URI, CURRENT_LITERAL);
    private final static Name PARENT_NAME = NAME_FACTORY.create(Name.NS_DEFAULT_URI, PARENT_LITERAL);
    private final static Name ROOT_NAME = NAME_FACTORY.create(Name.NS_DEFAULT_URI, "");

    private static final Path.Element CURRENT_ELEMENT = new SpecialElement(CURRENT_NAME);
    private static final Path.Element PARENT_ELEMENT = new SpecialElement(PARENT_NAME);
    private static final Path.Element ROOT_ELEMENT = new SpecialElement(ROOT_NAME);

    /**
     * the root path
     */
    private static final Path ROOT = new PathImpl(new Path.Element[]{ROOT_ELEMENT}, true);
    private static final Path CURRENT_PATH = new PathImpl(new Path.Element[]{CURRENT_ELEMENT}, true);
    private static final Path PARENT_PATH = new PathImpl(new Path.Element[]{PARENT_ELEMENT}, true);

    private PathFactoryImpl() {}

    public static PathFactory getInstance() {
        return FACTORY;
    }

    //--------------------------------------------------------< PathFactory >---
    /**
     * @see PathFactory#create(Path, Path, boolean)
     */
    public Path create(Path parent, Path relPath, boolean normalize) throws IllegalArgumentException, RepositoryException {
        if (relPath.isAbsolute()) {
            throw new IllegalArgumentException("relPath is not a relative path");
        }
        List l = new ArrayList();
        l.addAll(Arrays.asList(parent.getElements()));
        l.addAll(Arrays.asList(relPath.getElements()));

        Builder pb = new Builder(l);
        Path path = pb.getPath();
        if (normalize) {
            return path.getNormalizedPath();
        } else {
            return path;
        }
    }

    /**
     * @see PathFactory#create(Path, Name, boolean)
     */
    public Path create(Path parent, Name name, boolean normalize) throws RepositoryException {
        List elements = new ArrayList();
        elements.addAll(Arrays.asList(parent.getElements()));
        elements.add(createElement(name));

        Builder pb = new Builder(elements);
        Path path = pb.getPath();
        if (normalize) {
            return path.getNormalizedPath();
        } else {
            return path;
        }
    }

    /**
     * @see PathFactory#create(Path, Name, int, boolean)
     */
    public Path create(Path parent, Name name, int index, boolean normalize) throws IllegalArgumentException, RepositoryException {
        List elements = new ArrayList();
        elements.addAll(Arrays.asList(parent.getElements()));
        elements.add(createElement(name, index));

        Builder pb = new Builder(elements);
        Path path = pb.getPath();
        if (normalize) {
            return path.getNormalizedPath();
        } else {
            return path;
        }
    }

    /**
     * @see PathFactory#create(Name)
     */
    public Path create(Name name) throws IllegalArgumentException {
        Path.Element elem = createElement(name);
        return new Builder(new Path.Element[]{elem}).getPath();
    }

    /**
     * @see PathFactory#create(Name, int)
     */
    public Path create(Name name, int index) throws IllegalArgumentException {
        if (index < Path.INDEX_UNDEFINED) {
            throw new IllegalArgumentException("Index must not be negative: " + index);
        }
        Path.Element elem = createElement(name, index);
        return new Builder(new Path.Element[]{elem}).getPath();
    }

    /**
     * @see PathFactory#create(Path.Element[])
     */
    public Path create(Path.Element[] elements) throws IllegalArgumentException {
        return new Builder(elements).getPath();
    }

    /**
     * @see PathFactory#create(String)
     */
    public Path create(String pathString) throws IllegalArgumentException {
        if (pathString == null || "".equals(pathString)) {
            throw new IllegalArgumentException("Invalid Path literal");
        }
        // split into path elements
        int lastPos = 0;
        int pos = pathString.indexOf(Path.DELIMITER);
        ArrayList list = new ArrayList();
        while (lastPos >= 0) {
            Path.Element elem;
            if (pos >= 0) {
                elem = createElement(pathString.substring(lastPos, pos));
                lastPos = pos + 1;
                pos = pathString.indexOf(Path.DELIMITER, lastPos);
            } else {
                elem = createElement(pathString.substring(lastPos));
                lastPos = -1;
            }
            list.add(elem);
        }
        return new Builder(list).getPath();
    }

    /**
     * @see PathFactory#createElement(Name)
     */
    public Path.Element createElement(Name name) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        } else if (name.equals(PARENT_NAME)) {
            return PARENT_ELEMENT;
        } else if (name.equals(CURRENT_NAME)) {
            return CURRENT_ELEMENT;
        } else if (name.equals(ROOT_NAME)) {
            return ROOT_ELEMENT;
        } else {
            return new Element(name, Path.INDEX_UNDEFINED);
        }
    }

    /**
     * @see PathFactory#createElement(Name, int)
     */
    public Path.Element createElement(Name name, int index) throws IllegalArgumentException {
        if (index < Path.INDEX_UNDEFINED) {
            throw new IllegalArgumentException("The index may not be negative.");
        } else if (name == null) {
            throw new IllegalArgumentException("The name must not be null");
        } else if (name.equals(PARENT_NAME)
                || name.equals(CURRENT_NAME)
                || name.equals(ROOT_NAME)) {
            throw new IllegalArgumentException(
                    "Special path elements (root, '.' and '..') can not have an explicit index.");
        } else {
            return new Element(name, index);
        }
    }

    /**
     * Create an element from the element string
     */
    private Path.Element createElement(String elementString) {
        if (elementString == null) {
            throw new IllegalArgumentException("null PathElement literal");
        }
        if (elementString.equals(ROOT_NAME.toString())) {
            return ROOT_ELEMENT;
        } else if (elementString.equals(CURRENT_LITERAL)) {
            return CURRENT_ELEMENT;
        } else if (elementString.equals(PARENT_LITERAL)) {
            return PARENT_ELEMENT;
        }

        int pos = elementString.indexOf('[');
        if (pos == -1) {
            Name name = NAME_FACTORY.create(elementString);
            return new Element(name, Path.INDEX_UNDEFINED);
        }
        Name name = NAME_FACTORY.create(elementString.substring(0, pos));
        int pos1 = elementString.indexOf(']');
        if (pos1 == -1) {
            throw new IllegalArgumentException("invalid PathElement literal: " + elementString + " (missing ']')");
        }
        try {
            int index = Integer.valueOf(elementString.substring(pos + 1, pos1)).intValue();
            if (index < 1) {
                throw new IllegalArgumentException("invalid PathElement literal: " + elementString + " (index is 1-based)");
            }
            return new Element(name, index);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid PathElement literal: " + elementString + " (" + e.getMessage() + ")");
        }
    }

    /**
     * @see PathFactory#getCurrentElement()
     */
    public Path.Element getCurrentElement() {
        return CURRENT_ELEMENT;
    }

    /**
     * @see PathFactory#getParentElement()
     */
    public Path.Element getParentElement() {
        return PARENT_ELEMENT;
    }

    /**
     * @see PathFactory#getRootElement()
     */
    public Path.Element getRootElement() {
        return ROOT_ELEMENT;
    }

    /**
     * @see PathFactory#getRootPath()
     */
    public Path getRootPath() {
        return ROOT;
    }

    //--------------------------------------------------------------------------
    private static final class PathImpl implements Path {

        /**
         * the elements of this path
         */
        private final Path.Element[] elements;

        /**
         * flag indicating if this path is normalized
         */
        private final boolean normalized;

        /**
         * flag indicating if this path is absolute
         */
        private final boolean absolute;

        /**
         * the cached hashcode of this path
         */
        private transient int hash = 0;

        /**
         * the cached 'toString' of this path
         */
        private transient String string;

        private PathImpl(Path.Element[] elements, boolean isNormalized) {
            if (elements == null || elements.length == 0) {
                throw new IllegalArgumentException("Empty paths are not allowed");
            }
            this.elements = elements;
            this.absolute = elements[0].denotesRoot();
            this.normalized = isNormalized;
        }

        /**
         * @see Path#denotesRoot()
         */
        public boolean denotesRoot() {
            return absolute && elements.length == 1;
        }

        /**
         * @see Path#isAbsolute()
         */
        public boolean isAbsolute() {
            return absolute;
        }

        /**
         * @see Path#isCanonical()
         */
        public boolean isCanonical() {
            return absolute && normalized;
        }

        /**
         * @see Path#isNormalized()
         */
        public boolean isNormalized() {
            return normalized;
        }

        /**
         * @see Path#getNormalizedPath()
         */
        public Path getNormalizedPath() {
            if (isNormalized()) {
                return this;
            }
            LinkedList queue = new LinkedList();
            Path.Element last = PARENT_ELEMENT;
            for (int i = 0; i < elements.length; i++) {
                Path.Element elem = elements[i];
                if (elem.denotesParent() && !last.denotesParent()) {
                    queue.removeLast();
                    if (queue.isEmpty()) {
                        last = PARENT_ELEMENT;
                    } else {
                        last = (Path.Element) queue.getLast();
                    }
                } else if (!elem.denotesCurrent()) {
                    last = elem;
                    queue.add(last);
                }
            }
            if (queue.isEmpty()) {
                return CURRENT_PATH;
            }
            boolean isNormalized = true;
            return new PathImpl((Path.Element[]) queue.toArray(new Element[queue.size()]), isNormalized);
        }

        /**
         * @see Path#getCanonicalPath()
         */
        public Path getCanonicalPath() throws RepositoryException {
            if (isCanonical()) {
                return this;
            }
            if (!isAbsolute()) {
                throw new RepositoryException("Only an absolute path can be canonicalized.");
            }
            return getNormalizedPath();
        }

        /**
         * @see Path#computeRelativePath(Path)
         */
        public Path computeRelativePath(Path other) throws RepositoryException {
            if (other == null) {
                throw new IllegalArgumentException("null argument");
            }

            // make sure both paths are absolute
            if (!isAbsolute() || !other.isAbsolute()) {
                throw new RepositoryException("Cannot compute relative path from relative paths");
            }

            // make sure we're comparing canonical paths
            Path p0 = getCanonicalPath();
            Path p1 = other.getCanonicalPath();

            if (p0.equals(p1)) {
                // both paths are equal, the relative path is therefore '.'
                return CURRENT_PATH;
            }

            // determine length of common path fragment
            int lengthCommon = 0;
            Path.Element[] elems0 = p0.getElements();
            Path.Element[] elems1 = p1.getElements();
            for (int i = 0; i < elems0.length && i < elems1.length; i++) {
                if (!elems0[i].equals(elems1[i])) {
                    break;
                }
                lengthCommon++;
            }
            List l = new ArrayList();
            if (lengthCommon < elems0.length) {
                /**
                 * the common path fragment is an ancestor of this path;
                 * this has to be accounted for by prepending '..' elements
                 * to the relative path
                 */
                int tmp = elems0.length - lengthCommon;
                while (tmp-- > 0) {
                    l.add(0, PARENT_ELEMENT);
                }
            }
            // add remainder of other path
            for (int i = lengthCommon; i < elems1.length; i++) {
                l.add(elems1[i]);
            }
            return new Builder(l).getPath();
        }

        /**
         * @see Path#getAncestor(int)
         */
        public Path getAncestor(int degree) throws IllegalArgumentException, PathNotFoundException {
            if (degree < 0) {
                throw new IllegalArgumentException("degree must be >= 0");
            } else if (degree == 0) {
                return this.getNormalizedPath();
            }

            if (isAbsolute()) {
                Path.Element[] normElems = getNormalizedPath().getElements();
                int length = normElems.length - degree;
                if (length < 1) {
                    throw new PathNotFoundException("no such ancestor path of degree " + degree);
                }
                Path.Element[] ancestorElements = new Element[length];
                System.arraycopy(normElems, 0, ancestorElements, 0, length);
                return new PathImpl(ancestorElements, true);
            } else {
                Path.Element[] ancestorElements = new Element[elements.length + degree];
                System.arraycopy(elements, 0, ancestorElements, 0, elements.length);

                for (int i = elements.length; i < ancestorElements.length; i++) {
                    ancestorElements[i] = PARENT_ELEMENT;
                }
                return new PathImpl(ancestorElements, false).getNormalizedPath();
            }
        }

        /**
         * @see Path#getAncestorCount()
         */
        public int getAncestorCount() {
            return (isAbsolute()) ? getDepth() : -1;
        }

        /**
         * @see Path#getLength()
         */
        public int getLength() {
            return elements.length;
        }

        /**
         * @see Path#getDepth()
         */
        public int getDepth() {
            int depth = ROOT_DEPTH;
            for (int i = 0; i < elements.length; i++) {
                if (elements[i].denotesParent()) {
                    depth--;
                } else if (elements[i].denotesName()) {
                    // don't count root/current element.
                    depth++;
                }
            }
            return depth;
        }

        /**
         * @see Path#isEquivalentTo(Path)
         */
        public boolean isEquivalentTo(Path other) throws RepositoryException {
            if (other == null) {
                throw new IllegalArgumentException("null argument");
            }
            if (isAbsolute() != other.isAbsolute()) {
                throw new IllegalArgumentException("Cannot compare a relative path with an absolute path");
            }

            if (getDepth() != other.getDepth()) {
                return false;
            }

            Element[] elems0 = getNormalizedPath().getElements();
            Element[] elems1 = other.getNormalizedPath().getElements();

            if (elems0.length != elems1.length)
                return false;

            for (int k = 0; k < elems0.length; k++) {
                if (!elems0[k].equals(elems1[k]))
                    return false;
            }
            return true;
        }

        /**
         * @see Path#isAncestorOf(Path)
         */
        public boolean isAncestorOf(Path other) throws IllegalArgumentException, RepositoryException {
            if (other == null) {
                throw new IllegalArgumentException("null argument");
            }
            // make sure both paths are either absolute or relative
            if (isAbsolute() != other.isAbsolute()) {
                throw new IllegalArgumentException("Cannot compare a relative path with an absolute path");
            }

            int delta = other.getDepth() - getDepth();
            if (delta <= 0)
                return false;

            return isEquivalentTo(other.getAncestor(delta));
        }

        /**
         * @see Path#isDescendantOf(Path)
         */
        public boolean isDescendantOf(Path other) throws IllegalArgumentException, RepositoryException {
            if (other == null) {
                throw new IllegalArgumentException("Null argument");
            }
            return other.isAncestorOf(this);
        }

        /**
         * @see Path#subPath(int, int)
         */
        public Path subPath(int from, int to) throws IllegalArgumentException, RepositoryException {
            if (from < 0 || to > elements.length || from >= to) {
                throw new IllegalArgumentException();
            }
            if (!isNormalized()) {
                throw new RepositoryException("Cannot extract sub-Path from a non-normalized Path.");
            }
            Path.Element[] dest = new Path.Element[to-from];
            System.arraycopy(elements, from, dest, 0, dest.length);
            Builder pb = new Builder(dest);
            return pb.getPath();
        }

        /**
         * @see Path#getNameElement()
         */
        public Element getNameElement() {
            return elements[elements.length - 1];
        }

        /**
         * @see Path#getString()
         */
        public String getString() {
            return toString();
        }

        /**
         * @see Path#getElements()
         */
        public Element[] getElements() {
            return elements;
        }

        //---------------------------------------------------------< Object >---
        /**
         * Returns the internal string representation of this <code>Path</code>.
         * <p/>
         * Note that the returned string is not a valid JCR path, i.e. the
         * namespace URI's of the individual path elements are not replaced with
         * their mapped prefixes.
         *
         * @return the internal string representation of this <code>Path</code>.
         */
        public String toString() {
            // Path is immutable, we can store the string representation
            if (string == null) {
                StringBuffer sb = new StringBuffer();
                for (int i = 0; i < elements.length; i++) {
                    if (i > 0) {
                        sb.append(Path.DELIMITER);
                    }
                    Path.Element element = elements[i];
                    String elem = element.toString();
                    sb.append(elem);
                }
                string = sb.toString();
            }
            return string;
        }

        /**
         * Returns a hash code value for this path.
         *
         * @return a hash code value for this path.
         * @see Object#hashCode()
         */
        public int hashCode() {
            // Path is immutable, we can store the computed hash code value
            int h = hash;
            if (h == 0) {
                h = 17;
                for (int i = 0; i < elements.length; i++) {
                    h = 37 * h + elements[i].hashCode();
                }
                hash = h;
            }
            return h;
        }

        /**
         * Compares the specified object with this path for equality.
         *
         * @param obj the object to be compared for equality with this path.
         * @return <tt>true</tt> if the specified object is equal to this path.
         */
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Path) {
                Path other = (Path) obj;
                return Arrays.equals(elements, other.getElements());
            }
            return false;
        }
    }

    //-------------------------------------------------------< Path.Element >---
    /**
     * Object representation of a single JCR path element.
     *
     * @see Path.Element
     */
    private static class Element implements Path.Element {

        /**
         * Qualified name of the path element.
         */
        private final Name name;

        /**
         * Optional index of the path element. Set to zero, if not
         * explicitly specified, otherwise contains the 1-based index.
         */
        private final int index;

        /**
         * Private constructor for creating a path element with the given
         * qualified name and index. Instead of using this constructor directly
         * the factory methods {@link PathFactory#createElement(Name)} and
         * {@link PathFactory#create(Name, int)} should be used.
         *
         * @param name  qualified name
         * @param index index
         */
        private Element(Name name, int index) {
            this.index = index;
            this.name = name;
        }

        /**
         * @see Path.Element#getName()
         */
        public Name getName() {
            return name;
        }

        /**
         * @see Path.Element#getIndex()
         */
        public int getIndex() {
            return index;
        }

        /**
         * @see Path.Element#getNormalizedIndex()
         */
        public int getNormalizedIndex() {
            if (index == Path.INDEX_UNDEFINED) {
                return Path.INDEX_DEFAULT;
            } else {
                return index;
            }
        }

        /**
         * @return always returns false.
         * @see Path.Element#denotesRoot()
         */
        public boolean denotesRoot() {
            return false;
        }

        /**
         * @return always returns false.
         * @see Path.Element#denotesParent()
         */
        public boolean denotesParent() {
            return false;
        }

        /**
         * @return always returns false.
         * @see Path.Element#denotesCurrent()
         */
        public boolean denotesCurrent() {
            return false;
        }

        /**
         * @return always returns true.
         * @see Path.Element#denotesName()
         */
        public boolean denotesName() {
            return true;
        }

        /**
         * @see Path.Element#getString()
         */
        public String getString() {
            return toString();
        }

        /**
         * Returns a string representation of this path element. Note that
         * the path element name is expressed using the <code>{uri}name</code>
         * syntax.
         *
         * @return string representation of the path element
         * @see Object#toString()
         */
        public String toString() {
            StringBuffer sb = new StringBuffer();
            // name
            sb.append(name.toString());
            // index
            if (index > Path.INDEX_DEFAULT) {
                sb.append('[');
                sb.append(index);
                sb.append(']');
            }
            return sb.toString();
        }

        /**
         * Computes a hash code for this path element.
         *
         * @return hash code
         * @see Object#hashCode()
         */
        public int hashCode() {
            int h = 17;
            h = 37 * h + getNormalizedIndex();
            h = 37 * h + name.hashCode();
            return h;
        }

        /**
         * Check for path element equality. Returns true if the given
         * object is a PathElement and contains the same name and index
         * as this one.
         *
         * @param obj the object to compare with
         * @return <code>true</code> if the path elements are equal
         * @see Object#equals(Object)
         */
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Path.Element) {
                Path.Element other = (Path.Element) obj;
                return name.equals(other.getName())
                        && getNormalizedIndex() == other.getNormalizedIndex();
            }
            return false;
        }
    }

    /**
     * Object representation of a special JCR path element notably the root, the
     * current and the parent element.
     */
    private static final class SpecialElement extends Element {

        private final static int ROOT = 1;
        private final static int CURRENT = 2;
        private final static int PARENT = 4;

        private final int type;

        private SpecialElement(Name name) {
            super(name, Path.INDEX_UNDEFINED);
            if (ROOT_NAME.equals(name)) {
                type = ROOT;
            } else if (CURRENT_NAME.equals(name)) {
                type = CURRENT;
            } else if (PARENT_NAME.equals(name)) {
                type = PARENT;
            } else {
                throw new IllegalArgumentException();
            }
        }

        /**
         * @return true if this is the {@link #ROOT root element}.
         * @see Path.Element#denotesRoot()
         */
        public boolean denotesRoot() {
            return type == ROOT;
        }

        /**
         * @return true if this is the {@link #PARENT parent element}.
         * @see Path.Element#denotesParent()
         */
        public boolean denotesParent() {
            return type == PARENT;
        }

        /**
         * @return true if this is the {@link #CURRENT current element}.
         * @see Path.Element#denotesCurrent()
         */
        public boolean denotesCurrent() {
            return type == CURRENT;
        }

        /**
         * @return Always returns false.
         * @see Path.Element#denotesName()
         */
        public boolean denotesName() {
            return false;
        }
    }

    /**
     * Builder internal class
     */
    private static final class Builder {

        /**
         * the lpath elements of the constructed path
         */
        private final Path.Element[] elements;

        /**
         * flag indicating if the current path is normalized
         */
        private boolean isNormalized;

        /**
         * Creates a new Builder and initialized it with the given path
         * elements.
         *
         * @param elemList
         * @throws IllegalArgumentException if the given elements array is null
         * or has a length less than 1;
         */
        private Builder(List elemList) {
            this((Path.Element[]) elemList.toArray(new Path.Element[elemList.size()]));
        }

        /**
         * Creates a new Builder and initialized it with the given path
         * elements.
         *
         * @param elements
         * @throws IllegalArgumentException if the given elements array is null
         * or has a length less than 1;
         */
        private Builder(Path.Element[] elements) {
            if (elements == null || elements.length == 0) {
                throw new IllegalArgumentException("Cannot build path from null or 0 elements.");
            }

            this.elements = elements;
            if (elements.length == 1) {
                isNormalized = true;
            } else {
                boolean absolute = elements[0].denotesRoot();
                isNormalized = true;
                int parents = 0;
                int named = 0;
                for (int i = 0; i < elements.length; i++) {
                    Path.Element elem = elements[i];
                    if (elem.denotesName()) {
                        named++;
                    } else if (elem.denotesRoot()) {
                        if (i > 0) {
                            throw new IllegalArgumentException("Invalid path: The root element may only occur at the beginning.");
                        }
                    } else  if (elem.denotesParent()) {
                        parents++;
                        if (absolute || named > 0) {
                            isNormalized = false;
                        }
                    } else /* current element */ {
                        isNormalized = false;
                    }
                }
                if (absolute && parents > named) {
                    throw new IllegalArgumentException("Invalid path: Too many parent elements.");
                }
            }
        }

        /**
         * Assembles the built path and returns a new {@link Path}.
         *
         * @return a new {@link Path}
         */
        private Path getPath() {
            // special path with a single element
            if (elements.length == 1) {
                if (elements[0].denotesRoot()) {
                    return PathFactoryImpl.ROOT;
                }
                if (elements[0].denotesParent()) {
                    return PathFactoryImpl.PARENT_PATH;
                }
                if (elements[0].denotesCurrent()) {
                    return PathFactoryImpl.CURRENT_PATH;
                }
            }

            // default: build a new path
            // no need to check the path format, assuming all names correct
            return new PathImpl(elements, isNormalized);
        }
    }
}
