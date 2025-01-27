/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Jun 6, 2020 (hornm): created
 */
package org.knime.core.node.workflow.action;

import java.util.List;

import org.knime.core.node.NodeFactory;
import org.knime.core.node.context.ModifiableNodeCreationConfiguration;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.node.workflow.ConnectionContainer;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.WorkflowManager;

/**
 * Result of the replace node operation, e.g.,
 * {@link WorkflowManager#replaceNode(NodeID, ModifiableNodeCreationConfiguration)}.
 *
 * Main purpose is 'undo'.
 *
 * @noreference This class is not intended to be referenced by clients.
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 * @since 4.2
 */
public final class ReplaceNodeResult {

    private final WorkflowManager m_wfm;

    private final NodeID m_replacedNodeID;

    private final List<ConnectionContainer> m_removedConnections;

    private final ModifiableNodeCreationConfiguration m_nodeCreationConfig;

    private final NodeFactory<?> m_originalNodeFactory;


    /**
     * New instance.
     *
     * @param wfm the host workflow manager
     * @param replacedNodeID the id of the newly created node
     * @param removedConnections the connections that couldn't be restored after the replacement
     * @param originalNodeCreationConfig the original creation config of the old node (for the undo)
     * @deprecated use
     *             {@link #ReplaceNodeResult(WorkflowManager, NodeID, List, ModifiableNodeCreationConfiguration, NodeFactory)}
     *             instead
     */
    @Deprecated(forRemoval = true)
    public ReplaceNodeResult(final WorkflowManager wfm, final NodeID replacedNodeID,
        final List<ConnectionContainer> removedConnections,
        final ModifiableNodeCreationConfiguration originalNodeCreationConfig) {
        this(wfm, replacedNodeID, removedConnections, originalNodeCreationConfig, null);
    }

    /**
     * New instance.
     *
     * @param wfm the host workflow manager
     * @param replacedNodeID the id of the newly created node
     * @param removedConnections the connections that couldn't be restored after the replacement
     * @param originalNodeCreationConfig the original creation config of the old node (for the undo)
     * @param originalNodeFactory factory of the deleted node
     */
    public ReplaceNodeResult(final WorkflowManager wfm, final NodeID replacedNodeID,
        final List<ConnectionContainer> removedConnections,
        final ModifiableNodeCreationConfiguration originalNodeCreationConfig,
        final NodeFactory<?> originalNodeFactory) {
        CheckUtils.checkNotNull(wfm);
        CheckUtils.checkNotNull(replacedNodeID);
        CheckUtils.checkNotNull(removedConnections);
        CheckUtils.checkNotNull(removedConnections);
        m_wfm = wfm;
        m_replacedNodeID = replacedNodeID;
        m_removedConnections = removedConnections;
        m_nodeCreationConfig = originalNodeCreationConfig;
        m_originalNodeFactory = originalNodeFactory;
    }

    /**
     * @return whether the undo operation can be performed
     */
    public boolean canUndo() {
        return m_wfm.canReplaceNode(m_replacedNodeID);
    }

    /**
     * Performs the undo.
     */
    public void undo() {
        if (m_originalNodeFactory == null) {
            m_wfm.replaceNode(m_replacedNodeID, m_nodeCreationConfig);
        } else {
            m_wfm.replaceNode(m_replacedNodeID, m_nodeCreationConfig, m_originalNodeFactory);
        }
        m_removedConnections.stream()
            .filter(c -> m_wfm.canAddConnection(c.getSource(), c.getSourcePort(), c.getDest(), c.getDestPort()))
            .forEach(c -> m_wfm.addConnection(c.getSource(), c.getSourcePort(), c.getDest(), c.getDestPort()));
    }

}
