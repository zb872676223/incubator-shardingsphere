/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.text.query;

import lombok.SneakyThrows;
import org.apache.shardingsphere.core.constant.ShardingConstant;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.ConnectionStatus;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryData;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryResponse;
import org.apache.shardingsphere.shardingproxy.backend.schema.LogicSchemas;
import org.apache.shardingsphere.shardingproxy.backend.schema.ShardingSchema;
import org.apache.shardingsphere.shardingproxy.backend.text.TextProtocolBackendHandler;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.MySQLCommandPacketType;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.text.query.fixture.ShardingTransactionManagerFixture;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.transaction.core.TransactionOperationType;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class MySQLComQueryPacketTest {
    
    @Mock
    private MySQLPacketPayload payload;
    
    private BackendConnection backendConnection = new BackendConnection(TransactionType.LOCAL);
    
    @Before
    public void setUp() {
        setShardingSchemas();
        backendConnection.setCurrentSchema(ShardingConstant.LOGIC_SCHEMA_NAME);
    }
    
    @After
    public void tearDown() {
        ShardingTransactionManagerFixture.getInvocations().clear();
    }
    
    @SneakyThrows
    private void setShardingSchemas() {
        ShardingSchema shardingSchema = mock(ShardingSchema.class);
        Map<String, ShardingSchema> shardingSchemas = new HashMap<>();
        shardingSchemas.put(ShardingConstant.LOGIC_SCHEMA_NAME, shardingSchema);
        Field field = LogicSchemas.class.getDeclaredField("logicSchemas");
        field.setAccessible(true);
        field.set(LogicSchemas.getInstance(), shardingSchemas);
    }
    
    @Test
    public void assertWrite() {
        MySQLComQueryPacket actual = new MySQLComQueryPacket("SELECT id FROM tbl");
        actual.write(payload);
        verify(payload).writeInt1(MySQLCommandPacketType.COM_QUERY.getValue());
        verify(payload).writeStringEOF("SELECT id FROM tbl");
    }
    
    @Test
    public void assertExecuteWithoutTransaction() throws SQLException {
        when(payload.readStringEOF()).thenReturn("SELECT id FROM tbl");
        MySQLComQueryPacket packet = new MySQLComQueryPacket(payload, backendConnection);
        QueryResponse queryResponse = mock(QueryResponse.class);
        setBackendHandler(packet, queryResponse);
        Collection<MySQLPacket> actual = packet.execute();
        assertThat(actual.size(), is(2));
        assertTrue(packet.next());
        assertThat(packet.getQueryData().getSequenceId(), is(3));
        assertFalse(packet.next());
    }
    
    @SneakyThrows
    private void setBackendHandler(final MySQLComQueryPacket packet, final QueryResponse queryResponse) {
        TextProtocolBackendHandler textProtocolBackendHandler = mock(TextProtocolBackendHandler.class);
        when(textProtocolBackendHandler.next()).thenReturn(true, false);
        when(textProtocolBackendHandler.getQueryData()).thenReturn(new QueryData(Collections.singletonList(Types.VARCHAR), Collections.<Object>singletonList("id")));
        when(textProtocolBackendHandler.execute()).thenReturn(queryResponse);
        when(textProtocolBackendHandler.next()).thenReturn(true, false);
        when(textProtocolBackendHandler.getQueryData()).thenReturn(new QueryData(Collections.singletonList(Types.BIGINT), Collections.<Object>singletonList(99999L)));
        Field field = MySQLComQueryPacket.class.getDeclaredField("textProtocolBackendHandler");
        field.setAccessible(true);
        field.set(packet, textProtocolBackendHandler);
    }
    
    @Test
    public void assertExecuteTCLWithLocalTransaction() {
        when(payload.readStringEOF()).thenReturn("COMMIT");
        MySQLComQueryPacket packet = new MySQLComQueryPacket(payload, backendConnection);
        backendConnection.getStateHandler().getAndSetStatus(ConnectionStatus.TRANSACTION);
        Collection<MySQLPacket> actual = packet.execute();
        assertThat(actual.size(), is(1));
        assertOKPacket(actual.iterator().next());
    }
    
    @Test
    public void assertExecuteTCLWithXATransaction() {
        backendConnection.setTransactionType(TransactionType.XA);
        when(payload.readStringEOF()).thenReturn("ROLLBACK");
        MySQLComQueryPacket packet = new MySQLComQueryPacket(payload, backendConnection);
        backendConnection.getStateHandler().getAndSetStatus(ConnectionStatus.TRANSACTION);
        Collection<MySQLPacket> actual = packet.execute();
        assertThat(actual.size(), is(1));
        assertOKPacket(actual.iterator().next());
        assertTrue(ShardingTransactionManagerFixture.getInvocations().contains(TransactionOperationType.ROLLBACK));
    }
    
    @Test
    public void assertExecuteRollbackWithXATransaction() {
        backendConnection.setTransactionType(TransactionType.XA);
        when(payload.readStringEOF()).thenReturn("COMMIT");
        MySQLComQueryPacket packet = new MySQLComQueryPacket(payload, backendConnection);
        backendConnection.getStateHandler().getAndSetStatus(ConnectionStatus.TRANSACTION);
        Collection<MySQLPacket> actual = packet.execute();
        assertThat(actual.size(), is(1));
        assertOKPacket(actual.iterator().next());
        assertTrue(ShardingTransactionManagerFixture.getInvocations().contains(TransactionOperationType.COMMIT));
    }
    
    private void assertOKPacket(final MySQLPacket actual) {
        assertThat(actual.getSequenceId(), is(1));
        assertThat(actual, instanceOf(MySQLOKPacket.class));
    }
}
