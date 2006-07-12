/*
 * $URL$
 * $Id$
 *
 * Copyright 1997-2005 Day Management AG
 * Barfuesserplatz 6, 4001 Basel, Switzerland
 * All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * Day Management AG, ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Day.
 */
package org.apache.jackrabbit.webdav.observation;

import org.apache.jackrabbit.webdav.xml.Namespace;
import org.apache.jackrabbit.webdav.xml.DomUtil;
import org.apache.jackrabbit.webdav.xml.XmlSerializable;
import org.apache.jackrabbit.webdav.xml.ElementIterator;
import org.w3c.dom.Element;
import org.w3c.dom.Document;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * <code>DefaultEventType</code> defines a simple EventType implementation that
 * only consists of a qualified event name consisting of namespace plus local
 * name. 
 */
public class DefaultEventType implements EventType {

    private static final Map eventTypes = new HashMap();

    private final String localName;
    private final Namespace namespace;

    /**
     * Avoid instanciation of <code>DefaultEventType</code>. Since the amount
     * of available (and registered) events is considered to be limited, the
     * static {@link #create(String, Namespace) method is defined.
     *
     * @param localName
     * @param namespace
     */
    private DefaultEventType(String localName, Namespace namespace) {
        this.localName = localName;
        this.namespace = namespace;
    }

    /**
     * Factory method to create a new <code>EventType</code>.
     *
     * @param localName
     * @param namespace
     * @return
     */
    public static EventType create(String localName, Namespace namespace) {
        if (localName == null || "".equals(localName)) {
            throw new IllegalArgumentException("null and '' are not valid local names of an event type.");
        }
        String key = DomUtil.getQualifiedName(localName, namespace);
        if (eventTypes.containsKey(key)) {
            return (EventType) eventTypes.get(key);
        } else {
            EventType type = new DefaultEventType(localName, namespace);
            eventTypes.put(key, type);
            return type;
        }
    }

    /**
     * Retrieves one or multiple <code>EventType</code>s from the 'eventtype'
     * Xml element. While a subscription may register multiple types (thus
     * the 'eventtype' contains multiple child elements), a single event may only
     * refer to one single type.
     *
     * @param eventType
     * @return
     */
    public static EventType[] createFromXml(Element eventType) {
        if (!DomUtil.matches(eventType, ObservationConstants.XML_EVENTTYPE, ObservationConstants.NAMESPACE)) {
            throw new IllegalArgumentException("'eventtype' element expected which contains a least a single child element.");
        }

        List etypes = new ArrayList();
        ElementIterator it = DomUtil.getChildren(eventType);
        while (it.hasNext()) {
            Element el = it.nextElement();
            etypes.add(create(el.getLocalName(), DomUtil.getNamespace(el)));
        }
        return (EventType[]) etypes.toArray(new EventType[etypes.size()]);
    }

    //----------------------------------------------------------< EventType >---
    /**
     * @see EventType#getName()
     */
    public String getName() {
        return localName;
    }

    /**
     * @see EventType#getNamespace()
     */
    public Namespace getNamespace() {
        return namespace;
    }

    //----------------------------------------------------< XmlSerializable >---
    /**
     * Returns a single empty Xml element where namespace and local name of this
     * event type define the elements name.
     * <pre>
     * EventType.create("someevent", Namespace.getNamespace("F", "http://www.foo.bar/eventtypes"));
     *
     * returns the following element upon call of toXml:
     *
     * &lt;F:someevent xmlns:F="http://www.foo.bar/eventtypes" /&gt;
     * </pre>
     *
     * @see XmlSerializable#toXml(Document)
     */
    public Element toXml(Document document) {
        return DomUtil.createElement(document, localName, namespace);
    }
}
