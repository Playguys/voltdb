/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.hsqldb_voltpatches;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Used to fake generate XML without actually generating the text and parsing it.
 * A performance optimization, and something of a simplicity win
 *
 */
public class VoltXMLElement {

    public String name;
    public final Map<String, String> attributes = new TreeMap<String, String>();
    public final List<VoltXMLElement> children = new ArrayList<VoltXMLElement>();

    public VoltXMLElement(String  name) {
        this.name = name;
    }

    public VoltXMLElement withValue(String key, String value) {
        attributes.put(key, value);
        return this;
    }

    /**
     * The elements generated by the HSQL output have the name field for
     * tables, columns, indexes, etc set to the type of object, and the actual
     * name (like, table name) is in the name attribute, if it exists.  If the
     * name attribute does not exist, then we fall back to the minstring
     * representation of the tree.  Essentially, an attribute named object is
     * sufficiently unique-ified by the name, and so we can detect and
     * represent changes to it, but changes to unnamed objects will just get
     * represented by element deletion and addition.
     *
     * This has some unfortunately side effects for some interesting top-level
     * elements that we work around by manually forcing them to have the same
     * attribute name as their element name.  In particular, the top level
     * 'databaseschema' and the collections of columns, indexes, and
     * constraints for a table.
     */
    public String getUniqueName() {
        if (attributes.containsKey("name")) {
            return name + attributes.get("name");
        }
        else {
            return toMinString();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        append(sb, 0);
        return sb.toString();
    }

    private void append(StringBuilder sb, int indent) {
        sb.append("ELEMENT: ").append(name);
        for (Entry<String, String> e : attributes.entrySet()) {
            sb.append(Expression.voltIndentStr(indent+2, true, false))
              .append(e.getKey())
              .append(" = ")
              .append(e.getValue());
        }
        if ( ! children.isEmpty()) {
            sb.append(Expression.voltIndentStr(indent, true, false))
              .append("[");
            for (VoltXMLElement e : children) {
                sb.append(Expression.voltIndentStr(indent + 2, true, false));
                e.append(sb, indent + 2);
            }
        }
    }

    public VoltXMLElement duplicate() {
        VoltXMLElement retval = new VoltXMLElement(name);
        for (Entry<String, String> e : attributes.entrySet()) {
            retval.attributes.put(e.getKey(), e.getValue());
        }
        for (VoltXMLElement child : children) {
            retval.children.add(child.duplicate());
        }
        return retval;
    }

    /**
     * Given a name, recursively find all the children  with matching name, if any.
     */
    public List<VoltXMLElement> findChildrenRecursively(String name)
    {
        List<VoltXMLElement> retval = new ArrayList<VoltXMLElement>();
        for (VoltXMLElement vxe : children) {
            if (name.equals(vxe.name)) {
                retval.add(vxe);
            }
            retval.addAll(vxe.findChildrenRecursively(name));
        }
        return retval;
    }

    /**
     * Given a name, find all the immediate children  with matching name, if any.
     */
    public List<VoltXMLElement> findChildren(String name)
    {
        List<VoltXMLElement> retval = new ArrayList<VoltXMLElement>();
        for (VoltXMLElement vxe : children) {
            if (name.equals(vxe.name)) {
                retval.add(vxe);
            }
        }
        return retval;
    }

    /**
     * Given an value in the format of that returned by getUniqueName, find the
     * child element which matches, if any.
     */
    public VoltXMLElement findChild(String uniqueName)
    {
        for (VoltXMLElement vxe : children) {
            if (uniqueName.equals(vxe.getUniqueName())) {
                return vxe;
            }
        }
        return null;
    }

    /**
     * Given the element name ('table') and the attribute name ('foo'), find
     * the matching child element, if any
     */
    public VoltXMLElement findChild(String elementName, String attributeName)
    {
        String attName = attributeName;
        if (attName == null) {
            attName = "default";
        }
        return findChild(elementName + attName);
    }

    /**
     * Get a string representation that is designed to be as short as possible
     * with as much certainty of uniqueness as possible.
     * A SHA-1 hash would suffice, but here's hoping just dumping to a string is
     * faster. Will measure later.
     */
    public String toMinString() {
        StringBuilder sb = new StringBuilder();
        toMinString(sb);
        return sb.toString();
    }

    protected StringBuilder toMinString(StringBuilder sb) {
        sb.append("\tE").append(name).append('\t');
        for (Entry<String, String> e : attributes.entrySet()) {
            sb.append('\t').append(e.getKey());
            sb.append('\t').append(e.getValue());
        }
        sb.append("\t[");
        for (VoltXMLElement e : children) {
            e.toMinString(sb);
        }
        return sb;
    }

    /**
     * Represent the differences between two VoltXMLElements with the same
     * getUniqueName().
     */
    static public class VoltXMLDiff
    {
        final String m_name;
        List<VoltXMLElement> m_addedElements = new ArrayList<VoltXMLElement>();
        List<VoltXMLElement> m_removedElements = new ArrayList<VoltXMLElement>();
        Map<String, VoltXMLDiff> m_changedElements = new HashMap<String, VoltXMLDiff>();
        Map<String, String> m_addedAttributes = new HashMap<String, String>();
        Set<String> m_removedAttributes = new HashSet<String>();
        Map<String, String> m_changedAttributes = new HashMap<String, String>();
        // To maintain the final order of elements, brute force it and
        // just write down the order of elements present in the 'after' state
        SortedMap<String, Integer> m_elementOrder = new TreeMap<String, Integer>();

        // Takes the VoltXMLElement unique name
        public VoltXMLDiff(String name)
        {
            m_name = name;
        }

        public String getName()
        {
            return m_name;
        }

        public List<VoltXMLElement> getAddedNodes()
        {
            return m_addedElements;
        }

        public List<VoltXMLElement> getRemovedNodes()
        {
            return m_removedElements;
        }

        public Map<String, VoltXMLDiff> getChangedNodes()
        {
            return m_changedElements;
        }

        public Map<String, String> getAddedAttributes()
        {
            return m_addedAttributes;
        }

        public Set<String> getRemovedAttributes()
        {
            return m_removedAttributes;
        }

        public Map<String, String> getChangedAttributes()
        {
            return m_changedAttributes;
        }

        public boolean isEmpty()
        {
            return (m_addedElements.isEmpty() &&
                    m_removedElements.isEmpty() &&
                    m_changedElements.isEmpty() &&
                    m_addedAttributes.isEmpty() &&
                    m_removedAttributes.isEmpty() &&
                    m_changedAttributes.isEmpty());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("NAME: " + m_name + "\n");
            sb.append("ADDED: " + m_addedAttributes + "\n");
            sb.append("REMOVED: " + m_removedAttributes + "\n");
            sb.append("CHANGED: " + m_changedAttributes + "\n");
            sb.append("NEW CHILDREN:\n");
            for (VoltXMLElement add : m_addedElements) {
                sb.append(add.toString());
            }
            sb.append("DEAD CHILDREN:\n");
            for (VoltXMLElement remove : m_removedElements) {
                sb.append(remove.toString());
            }
            sb.append("CHANGED CHILDREN:\n");
            for (VoltXMLDiff change : m_changedElements.values()) {
                sb.append(change.toString());
            }
            sb.append("\n\n");
            return sb.toString();
        }
    }

    /**
     * Compute the diff necessary to turn the 'before' tree into the 'after'
     * tree.
     */
    static public VoltXMLDiff computeDiff(VoltXMLElement before, VoltXMLElement after)
    {
        // Top level call needs both names to match (I think this makes sense)
        if (!before.getUniqueName().equals(after.getUniqueName())) {
            // not sure this is best behavior, ponder as progress is made
            return null;
        }

        VoltXMLDiff result = new VoltXMLDiff(before.getUniqueName());
        // Short-circuit check for any differences first.  Can return early if there are no changes
        if (before.toMinString().equals(after.toMinString())) {
            return result;
        }

        // Store the final desired element order
        for (int i = 0; i < after.children.size(); i++) {
            VoltXMLElement child = after.children.get(i);
            result.m_elementOrder.put(child.getUniqueName(), i);
        }

        // first, check the attributes
        Set<String> firstKeys = before.attributes.keySet();
        Set<String> secondKeys = new HashSet<String>();
        secondKeys.addAll(after.attributes.keySet());
        // Do removed and changed attributes walking the first element's attributes
        for (String firstKey : firstKeys) {
            if (!secondKeys.contains(firstKey)) {
                result.m_removedAttributes.add(firstKey);
            }
            else if (!(after.attributes.get(firstKey).equals(before.attributes.get(firstKey)))) {
                result.m_changedAttributes.put(firstKey, after.attributes.get(firstKey));
            }
            // remove the firstKey from secondKeys to track things added
            secondKeys.remove(firstKey);
        }
        // everything in secondKeys should be something added
        for (String key : secondKeys) {
            result.m_addedAttributes.put(key, after.attributes.get(key));
        }

        // Now, need to check the children.  Each pair of children with the same names
        // need to be descended to look for changes
        // Probably more efficient ways to do this, but brute force it for now
        // Would be helpful if the underlying children objects were Maps rather than
        // Lists.
        Set<String> firstChildren = new HashSet<String>();
        for (VoltXMLElement child : before.children) {
            firstChildren.add(child.getUniqueName());
        }
        Set<String> secondChildren = new HashSet<String>();
        for (VoltXMLElement child : after.children) {
            secondChildren.add(child.getUniqueName());
        }
        Set<String> commonNames = new HashSet<String>();
        for (VoltXMLElement firstChild : before.children) {
            if (!secondChildren.contains(firstChild.getUniqueName())) {
                // Need to duplicate the
                result.m_removedElements.add(firstChild);
            }
            else {
                commonNames.add(firstChild.getUniqueName());
            }
        }
        for (VoltXMLElement secondChild : after.children) {
            if (!firstChildren.contains(secondChild.getUniqueName())) {
                result.m_addedElements.add(secondChild);
            }
            else {
                assert(commonNames.contains(secondChild.getUniqueName()));
            }
        }

        // This set contains uniquenames
        for (String name : commonNames) {
            VoltXMLDiff childDiff = computeDiff(before.findChild(name), after.findChild(name));
            if (!childDiff.isEmpty()) {
                result.m_changedElements.put(name, childDiff);
            }
        }

        return result;
    }

    public boolean applyDiff(VoltXMLDiff diff)
    {
        // Can only apply a diff to the root at which it was generated
        assert(getUniqueName().equals(diff.m_name));

        // Do the attribute changes
        attributes.putAll(diff.getAddedAttributes());
        for (String key : diff.getRemovedAttributes()) {
            attributes.remove(key);
        }
        for (Entry<String,String> e : diff.getChangedAttributes().entrySet()) {
            attributes.put(e.getKey(), e.getValue());
        }

        // Do the node removals and additions
        for (VoltXMLElement e : diff.getRemovedNodes()) {
            children.remove(findChild(e.getUniqueName()));
        }
        for (VoltXMLElement e : diff.getAddedNodes()) {
            children.add(e);
        }

        // To do the node changes, recursively apply the inner diffs to the children
        for (Entry<String, VoltXMLDiff> e : diff.getChangedNodes().entrySet()) {
            findChild(e.getKey()).applyDiff(e.getValue());
        }

        // Hacky, we don't write down the element order if there were no diffs
        if (diff.m_elementOrder.isEmpty()) {
            return true;
        }
        // Reorder the children
        // yes, not efficient.  Revisit on performance pass
        assert(children.size() == diff.m_elementOrder.size());
        List<VoltXMLElement> temp = new ArrayList<VoltXMLElement>();
        temp.addAll(children);
        for (VoltXMLElement child : temp) {
            String name = child.getUniqueName();
            Integer position = diff.m_elementOrder.get(name);
            if (position == null) {
                throw new RuntimeException("You have encountered an unexpected error.  Please contact VoltDB support, and include your current schema along with the DDL changes you were attempting to make.");
            }
            if (!name.equals(children.get(position).getUniqueName())) {
                children.set(position, child);
            }
        }

        return true;
    }
}
