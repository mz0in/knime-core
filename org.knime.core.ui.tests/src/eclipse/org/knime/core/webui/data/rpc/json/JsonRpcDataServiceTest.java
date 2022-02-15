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
 *   Sep 15, 2021 (hornm): created
 */
package org.knime.core.webui.data.rpc.json;

import static com.googlecode.jsonrpc4j.ErrorResolver.JsonError.CUSTOM_SERVER_ERROR_UPPER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.knime.core.node.workflow.NativeNodeContainer;
import org.knime.core.webui.data.rpc.json.impl.JsonRpcDataServiceImpl;
import org.knime.core.webui.data.rpc.json.impl.JsonRpcSingleServer;
import org.knime.core.webui.data.rpc.json.impl.ObjectMapperUtil;
import org.knime.core.webui.node.view.NodeView;
import org.knime.core.webui.node.view.NodeViewManager;
import org.knime.core.webui.node.view.NodeViewManagerTest;
import org.knime.core.webui.node.view.NodeViewTest;
import org.knime.core.webui.page.Page;
import org.knime.testing.util.WorkflowManagerUtil;

/**
 * Test for {@link JsonRpcDataService}-implementations.
 *
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 */
class JsonRpcDataServiceTest {

    /**
     * Tests {@link JsonRpcDataServiceImpl} when used in a {@link NodeView}.
     *
     * @throws IOException
     */
    @Test
    void testJsonRpcDataService() throws IOException {
        var wfm = WorkflowManagerUtil.createEmptyWorkflow();
        var page = Page.builder(() -> "content", "index.html").build();
        NativeNodeContainer nnc = NodeViewManagerTest.createNodeWithNodeView(wfm, m -> NodeViewTest.createNodeView(page,
            null, new JsonRpcDataServiceImpl(new JsonRpcSingleServer<MyService>(new MyService())), null));
        wfm.executeAllAndWaitUntilDone();

        var jsonRpcRequest = "{\"jsonrpc\":\"2.0\", \"id\":1, \"method\":\"myMethod\"}";
        String response = NodeViewManager.getInstance().callTextDataService(nnc, jsonRpcRequest);
        assertThat(response, is("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"my service method result\"}\n"));

        WorkflowManagerUtil.disposeWorkflow(wfm);
    }

    public static class MyService {

        public String myMethod() {
            return "my service method result"; // NOSONAR
        }
    }

    @Test
    void testJsonRpcDataServiceError() throws IOException {
        var wfm = WorkflowManagerUtil.createEmptyWorkflow();
        var page = Page.builder(() -> "content", "index.html").build();
        NativeNodeContainer nnc = NodeViewManagerTest.createNodeWithNodeView(wfm, m -> NodeViewTest.createNodeView(page,
            null, new JsonRpcDataServiceImpl(new JsonRpcSingleServer<ErroneusService>(new ErroneusService())), null));
        wfm.executeAllAndWaitUntilDone();

        var jsonRpcRequest = "{\"jsonrpc\":\"2.0\", \"id\":1, \"method\":\"erroneusMethod\", \"params\": [\"foo\"]}";
        String response = NodeViewManager.getInstance().callTextDataService(nnc, jsonRpcRequest);
        final var root = ObjectMapperUtil.getInstance().getObjectMapper().readTree(response);
        assertTrue(root.has("error"));
        final var error = root.get("error");
        assertTrue(error.has("code"));
        assertEquals(CUSTOM_SERVER_ERROR_UPPER, error.get("code").asInt());
        assertTrue(error.has("message"));
        assertEquals("foo", error.get("message").asText());
        assertTrue(error.has("data"));
        final var data = error.get("data");
        assertTrue(data.has("typeName"));
        assertEquals("java.lang.IllegalArgumentException", data.get("typeName").asText());
        assertTrue(data.has("stackTrace"));
        WorkflowManagerUtil.disposeWorkflow(wfm);
    }

    public static class ErroneusService {

        public String erroneusMethod(final String param) {
            throw new IllegalArgumentException(param);
        }
    }

}
