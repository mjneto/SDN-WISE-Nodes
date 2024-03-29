package com.github.sdnwiselab.sdnwise.cooja;

/*
 * Copyright (c) 2010, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 *
 */
import static com.github.sdnwiselab.sdnwise.cooja.Constants.*;
import com.github.sdnwiselab.sdnwise.flowtable.*;
import static com.github.sdnwiselab.sdnwise.flowtable.SetAction.*;
import static com.github.sdnwiselab.sdnwise.flowtable.Stats.SDN_WISE_RL_TTL_PERMANENT;
import static com.github.sdnwiselab.sdnwise.flowtable.Window.*;
import com.github.sdnwiselab.sdnwise.function.FunctionInterface;
import com.github.sdnwiselab.sdnwise.packet.*;
import static com.github.sdnwiselab.sdnwise.packet.ConfigAcceptedIdPacket.*;
import static com.github.sdnwiselab.sdnwise.packet.ConfigFunctionPacket.*;
import static com.github.sdnwiselab.sdnwise.packet.ConfigNodePacket.*;
import static com.github.sdnwiselab.sdnwise.packet.ConfigRulePacket.*;
import static com.github.sdnwiselab.sdnwise.packet.ConfigTimerPacket.*;
import static com.github.sdnwiselab.sdnwise.packet.NetworkPacket.*;
import com.github.sdnwiselab.sdnwise.util.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.*;
import org.contikios.cooja.*;
import org.contikios.cooja.interfaces.*;
import org.contikios.cooja.motes.AbstractApplicationMote;
import java.nio.charset.Charset;

/**
 * Example SdnWise mote.
 *
 * This mote is simulated in COOJA via the Imported App Mote Type.
 *
 * @author Sebastiano Milardo
 */
public abstract class AbstractMote extends AbstractApplicationMote {

    public ArrayList<Integer> statusRegister = new ArrayList<>();
    public ArrayList<byte[]> CopyPacket = new ArrayList<byte[]>();
    public ArrayList<String> StringCopyPacket = new ArrayList<String>();

    public String CopiaAgregada = "";

    private float Rate = 1;

    //variavel criada para contabilizar mensagens - Gabriel Gomes
    private static int countAggPayloadsMsg = 0;

    private Simulation simulation = null;
    private Random random = null;

    private int sentBytes;
    private int receivedBytes;

    private int sentDataBytes;
    private int receivedDataBytes;
    private int distanceFromSink;
    private int rssiSink;

    private int cntBeacon = 0;
    private int cntReport = 0;
    private int cntUpdTable = 0;

    ApplicationRadio radio = null;
    ApplicationLED leds = null;

    final ArrayBlockingQueue<NetworkPacket> flowTableQueue = new ArrayBlockingQueue<>(100);
    final ArrayBlockingQueue<NetworkPacket> txQueue = new ArrayBlockingQueue<>(100);

    int port,
            semaphore,
            flow_table_free_pos,
            accepted_id_free_pos,
            neighbors_number,
            net_id,
            cnt_beacon_max,
            cnt_report_max,
            cnt_updtable_max,
            cnt_sleep_max,
            ttl_max,
            rssi_min;

    NodeAddress addr;
    DatagramSocket socket;
    Battery battery = new Battery();
    ArrayList<Neighbor> neighborTable = new ArrayList<>(100);
    ArrayList<FlowTableEntry> flowTable = new ArrayList<>(100);
    ArrayList<NodeAddress> acceptedId = new ArrayList<>(100);

    HashMap<String, Object> adcRegister = new HashMap<>();
    HashMap<Integer, LinkedList<int[]>> functionBuffer = new HashMap<>();
    HashMap<Integer, FunctionInterface> functions = new HashMap<>();
    Logger MeasureLOGGER;

    public float getRate() {        
        return this.Rate;
    }

    public void setRate(float rate) {
        this.Rate = rate;
    }

    public AbstractMote() {
        super();
    }

    public AbstractMote(MoteType moteType, Simulation simulation) {
        super(moteType, simulation);
    }

    @Override
    public void execute(long time) {

        if (radio == null) {
            setup();
        }

        int delay = random.nextInt(10);

        simulation.scheduleEvent(
                new MoteTimeEvent(this, 0) {
                    @Override
                    public void execute(long t) {
                        timerTask();
                        logTask();
                    }
                },
                simulation.getSimulationTime()
                + (1000 + delay) * Simulation.MILLISECOND
        );
    }

    @Override
    public void receivedPacket(RadioPacket p) {
        byte[] packetData = p.getPacketData();
        NetworkPacket np = new NetworkPacket(packetData);
        if (np.getDst().isBroadcast()
                || np.getNxhop().equals(addr)
                || acceptedId.contains(np.getNxhop())) {
            rxHandler(new NetworkPacket(packetData), 255);
        }
    }

    @Override
    public void sentPacket(RadioPacket p) {
    }

    @Override
    public String toString() {
        return "SDN-WISE Mote " + getID();
    }

    public final void setDistanceFromSink(int num_hop_vs_sink) {
        this.distanceFromSink = num_hop_vs_sink;
    }

    public final void setRssiSink(int rssi_vs_sink) {
        this.rssiSink = rssi_vs_sink;
    }

    public final void setSemaphore(int semaforo) {
        this.semaphore = semaforo;
    }

    public final int getDistanceFromSink() {
        return distanceFromSink;
    }

    public final int getRssiSink() {
        return rssiSink;
    }

    public void initSdnWise() {

        cnt_beacon_max = SDN_WISE_DFLT_CNT_BEACON_MAX;
        cnt_report_max = SDN_WISE_DFLT_CNT_REPORT_MAX;
        cnt_updtable_max = SDN_WISE_DFLT_CNT_UPDTABLE_MAX;
        rssi_min = SDN_WISE_DFLT_RSSI_MIN;
        ttl_max = SDN_WISE_DFLT_TTL_MAX;

        battery = new Battery();
        //set the battery level to 85% of the maximum
        //battery.setBatteryLevel(battery.getBatteryLevel() * 85 / 100);
        //set the battery level to 15% of the maximum
        battery.setBatteryLevel(battery.getBatteryLevel() * 15 / 100);

        flow_table_free_pos = 1;
        accepted_id_free_pos = 0;
    }

    public final void radioTX(final NetworkPacket np) {
        sentBytes += np.getLen();
        if (np.getType() > 7 && !np.isRequest()) {
            sentDataBytes += np.getPayloadSize();
        }
        
        battery.transmitRadio(np.getLen());
        np.decrementTtl();
        RadioPacket pk = new COOJARadioPacket(np.toByteArray());
        if (radio.isTransmitting() || radio.isReceiving()) {
            simulation.scheduleEvent(
                    new MoteTimeEvent(this, 0) {
                        @Override
                        public void execute(long t) {
                            radioTX(np);
                        }
                    },
                    simulation.getSimulationTime()
                            + 1 * Simulation.MILLISECOND
            );
        } else {
            radio.startTransmittingPacket(pk, 1 * Simulation.MILLISECOND);
        }
    }

    public final NodeAddress getNextHopVsSink() {
        //log("Next Hop Size: " + flowTable.size());
        //log("getNextHop Action: " + flowTable.get(0).getActions().toString());
        return ((AbstractForwardAction) (flowTable.get(0).getActions().get(0))).getNextHop();
    }

    public final void rxData(DataPacket packet) {
        /* Aqui a cópia da mensagem é feita para o buffer de agregação
         *
         * mensagem do controlador
         *
         * O Ttl é 0 para que a mensagem inicial do nó seja exluida
         *
         * Agregação de dados ao receber o pacote (rxData()) por Gabriel Gomes
        */
        log("MsgArrived:" + new String(packet.getPayload()) + "\t" + "countMsgPayload:" + String.valueOf(countAggPayloadsMsg) +"\t"+ "Src:" + packet.getSrc() +"\t"+ "Dst:" + packet.getDst());
        if (packet.getDst().equals(addr)){
            //log("chegou no destino, origem: " + packet.getSrc() + " Com o conteudo: " + new String(packet.getPayload()));
            if(new String(packet.getPayload()).substring(0,3).equals("Agg")){
                String rateCtrl = new String(packet.getPayload()).split(":")[1];
                //log("Agg: " + Float.parseFloat(rateCtrl));
                setRate(Float.parseFloat(rateCtrl));
            }
        }
	
        if (isAcceptedIdPacket(packet)) {
            String payloadMsg = "";

            payloadMsg = payloadMsg + new String(packet.getPayload());
            String[] payloadMsgSplit = new String(payloadMsg).split(";");

            countAggPayloadsMsg += payloadMsgSplit.length;
            
            if (new String(packet.getPayload()).substring(0,1).equals("P")) {
                /*log("Volume: " + txt + " count: " + count 
                    + " tamanho: "+ packet.getPayload().length);*/
                log("Traffic1: " + (packet.getPayload().length * countAggPayloadsMsg) 
                    + " Battery P: " + String.valueOf(battery.getBatteryPercent() / 2.55)
                    + " Battery L: " + String.valueOf(battery.getBatteryLevel())); 
            }  

            SDN_WISE_Callback(packet);
            /*log("chegou no destino, origem : " + packet.getSrc() 
                + " Com o conteudo: " + new String(packet.getPayload()) 
                + " count: " + count + " tamanho: "+ packet.getPayload().length);*/
            log("Traffic2: " + (packet.getPayload().length * countAggPayloadsMsg) 
                + " Battery: " + String.valueOf(battery.getBatteryPercent() / 2.55)
                + " Battery L: " + String.valueOf(battery.getBatteryLevel()));
        
        } else if (isAcceptedIdAddress(packet.getNxhop())) { 

                if (new String(packet.getPayload()).substring(0,1).equals("P")) {
                    CopyMessage(packet); //comentado para teste 07/03/2023

                    packet.setTtl((byte) 0); //comentado para teste 07/03/2023
                }
                runFlowMatch(packet);
            }
    }

    public void rxBeacon(BeaconPacket bp, int rssi) {
        int index = getNeighborIndex(bp.getSrc());

        if (index != (SDN_WISE_NEIGHBORS_MAX + 1)) {
            if (index != -1) {
                neighborTable.get(index).setRssi(rssi);
                neighborTable.get(index).setBatt(bp.getBatt());
            } else {
                neighborTable.get(neighbors_number).setAddr(bp.getSrc());
                neighborTable.get(neighbors_number).setRssi(rssi);
                neighborTable.get(neighbors_number).setBatt(bp.getBatt());
                neighbors_number++;
            }
        }
    }

    public final void runFlowMatch(NetworkPacket packet) {
        int j, i, found = 0;
        for (j = 0; j < SDN_WISE_RLS_MAX; j++) {
            i = getActualFlowIndex(j);
            if (matchRule(flowTable.get(i), packet) == 1) {
                log("Matched Rule #" + j + " " + flowTable.get(i).toString());
                found = 1;
                for (AbstractAction a : flowTable.get(i).getActions()) {
                    runAction(a, packet);
                }
                flowTable.get(i).getStats()
                        .setCounter(flowTable.get(i).getStats().getCounter() + 1);
                break;
            }
        }
        if (found == 0) { //!found
            // It's necessary to send a rule/request if we have done the lookup
            // I must modify the source address with myself,
            //log("No Matched Rule - Packet: " + packet.toString());
            packet.setSrc(addr)
                    .setRequestFlag()
                    .setTtl(SDN_WISE_DFLT_TTL_MAX);
            controllerTX(packet);
        }
    }

    public abstract void rxConfig(ConfigPacket packet);

    public NodeAddress getActualSinkAddress() {
        //log("Sink Addr Size: " + flowTable.size());
        //log("getSinkAddr Window: " + flowTable.get(0).getWindows().toString());
        return new NodeAddress(flowTable.get(0).getWindows().get(0).getRhs());
    }

    public abstract void SDN_WISE_Callback(DataPacket packet);

    public abstract void controllerTX(NetworkPacket pck);

    public int marshalPacket(ConfigPacket packet) {
	//log("marshalpacket");
        int toBeSent = 0;
        int pos;
        boolean isWrite = packet.isWrite();
        int id = packet.getConfigId();
        int value = packet.getPayloadAt(1) * 256 + packet.getPayloadAt(2);
        if (isWrite) {
            switch (id) {
                case SDN_WISE_CNF_ID_ADDR:
                    addr = new NodeAddress(value);
                    break;
                case SDN_WISE_CNF_ID_NET_ID:
		    //log("net id");
                    net_id = packet.getPayloadAt(2);
                    break;
                case SDN_WISE_CNF_ID_CNT_BEACON_MAX:
                    cnt_beacon_max = value;
                    break;
                case SDN_WISE_CNF_ID_CNT_REPORT_MAX:
                    cnt_report_max = value;
                    break;
                case SDN_WISE_CNF_ID_CNT_UPDTABLE_MAX:
                    cnt_updtable_max = value;
                    break;
                case SDN_WISE_CNF_ID_CNT_SLEEP_MAX:
                    cnt_sleep_max = value;
                    break;
                case SDN_WISE_CNF_ID_TTL_MAX:
		    //log("ttl max");
                    ttl_max = packet.getPayloadAt(2);
                    break;
                case SDN_WISE_CNF_ID_RSSI_MIN:
		    //log("rssi min");
                    rssi_min = packet.getPayloadAt(2);
                    break;
                case SDN_WISE_CNF_ADD_ACCEPTED:
                    pos = searchAcceptedId(new NodeAddress(value));
                    if (pos == (SDN_WISE_ACCEPTED_ID_MAX + 1)) {
                        pos = searchAcceptedId(new NodeAddress(65535));
                        acceptedId.set(pos, new NodeAddress(value));
                    }
                    break;
                case SDN_WISE_CNF_REMOVE_ACCEPTED:
                    pos = searchAcceptedId(new NodeAddress(value));
                    if (pos != (SDN_WISE_ACCEPTED_ID_MAX + 1)) {
                        acceptedId.set(pos, new NodeAddress(65535));
                    }
                    break;
                case SDN_WISE_CNF_REMOVE_RULE_INDEX:
                    if (value != 0) {
                        flowTable.set(getActualFlowIndex(value), new FlowTableEntry());
                    }
                    break;
                case SDN_WISE_CNF_REMOVE_RULE:
                    //TODO
                    break;
                case SDN_WISE_CNF_ADD_FUNCTION:
		    //log("add function");
                    if (functionBuffer.get(value) == null) {
                        functionBuffer.put(value, new LinkedList<int[]>());
                    }
                    functionBuffer.get(value).add(Arrays.copyOfRange(
                            packet.toIntArray(), SDN_WISE_DFLT_HDR_LEN + 5,
                            packet.getLen()));
                    if (functionBuffer.get(value).size() == packet.getPayloadAt(4)) {
                        int total = 0;
                        for (int[] n : functionBuffer.get(value)) {
                            total += (n.length);
                        }
                        int pointer = 0;
                        byte[] func = new byte[total];
                        for (int[] n : functionBuffer.get(value)) {
                            for (int j = 0; j < n.length; j++) {
                                func[pointer] = (byte) n[j];
                                pointer++;
                            }
                        }
                        functions.put(value, createServiceInterface(func));
                        log("New Function Added at position: " + value);
                        functionBuffer.remove(value);
                    }
                    break;
                case SDN_WISE_CNF_REMOVE_FUNCTION:
                    functions.remove(value);
                    break;
                default:
                    break;
            }
        } else {
            toBeSent = 1;
            switch (id) {
                case SDN_WISE_CNF_ID_ADDR:
                    packet.setPayloadAt(addr.getHigh(), 1);
                    packet.setPayloadAt(addr.getLow(), 2);
                    break;
                case SDN_WISE_CNF_ID_NET_ID:
                    packet.setPayloadAt((byte) net_id, 2);
                    break;
                case SDN_WISE_CNF_ID_CNT_BEACON_MAX:
                    packet.setPayloadAt((byte) (cnt_beacon_max >> 8), 1);
                    packet.setPayloadAt((byte) (cnt_beacon_max), 2);
                    break;
                case SDN_WISE_CNF_ID_CNT_REPORT_MAX:
                    packet.setPayloadAt((byte) (cnt_report_max >> 8), 1);
                    packet.setPayloadAt((byte) (cnt_report_max), 2);
                    break;
                case SDN_WISE_CNF_ID_CNT_UPDTABLE_MAX:
                    packet.setPayloadAt((byte) (cnt_updtable_max >> 8), 1);
                    packet.setPayloadAt((byte) (cnt_updtable_max), 2);
                    break;
                case SDN_WISE_CNF_ID_CNT_SLEEP_MAX:
                    packet.setPayloadAt((byte) (cnt_sleep_max >> 8), 1);
                    packet.setPayloadAt((byte) (cnt_sleep_max), 2);
                    break;
                case SDN_WISE_CNF_ID_TTL_MAX:
                    packet.setPayloadAt((byte) ttl_max, 2);
                    break;
                case SDN_WISE_CNF_ID_RSSI_MIN:
                    packet.setPayloadAt((byte) rssi_min, 2);
                    break;
                case SDN_WISE_CNF_LIST_ACCEPTED:
                    toBeSent = 0;
                    ConfigAcceptedIdPacket packetList
                            = new ConfigAcceptedIdPacket(
                                    net_id,
                                    packet.getDst(),
                                    packet.getSrc());
                    packetList.setReadAcceptedAddressesValue();
                    int ii = 1;

                    for (int jj = 0; jj < SDN_WISE_ACCEPTED_ID_MAX; jj++) {
                        if (!acceptedId.get(jj).equals(new NodeAddress(65535))) {
                            packetList.setPayloadAt((acceptedId.get(jj)
                                    .getHigh()), ii);
                            ii++;
                            packetList.setPayloadAt((acceptedId.get(jj)
                                    .getLow()), ii);
                            ii++;
                        }
                    }
                    controllerTX(packetList);
                    break;
                case SDN_WISE_CNF_GET_RULE_INDEX:
		    //log("rule index");
                    toBeSent = 0;
                    ConfigRulePacket packetRule = new ConfigRulePacket(
                            net_id,
                            packet.getDst(),
                            packet.getSrc()
                    );
                    int jj = getActualFlowIndex(value);
                    packetRule.setRule(flowTable.get(jj))
                            .setPayloadAt(SDN_WISE_CNF_GET_RULE_INDEX, 0)
                            .setPayloadAt(packet.getPayloadAt(1), 1)
                            .setPayloadAt(packet.getPayloadAt(2), 2);
                    controllerTX(packetRule);
                    break;
                default:
                    break;
            }
        }
        return toBeSent;
    }

    private void timerTask() {
	//log("timer task");
        if (semaphore == 1 && battery.getBatteryLevel() > 0) {
            battery.keepAlive(1);

            cntBeacon++;
            cntReport++;
            cntUpdTable++;

            if ((cntBeacon) >= cnt_beacon_max) {
                cntBeacon = 0;
                radioTX(prepareBeacon());
            }

            if ((cntReport) >= cnt_report_max) {
                cntReport = 0;
                controllerTX(prepareReport());
            }

            //log("Updating Table: " + cntUpdTable + " / " + cnt_updtable_max);
            if ((cntUpdTable) >= cnt_updtable_max) {
                cntUpdTable = 0;
                updateTable();
            }
        }
        requestImmediateWakeup();
    }

    private void initFlowTable() {
        FlowTableEntry toSink = new FlowTableEntry();
        toSink.addWindow(new Window()
                .setOperator(SDN_WISE_EQUAL)
                .setSize(SDN_WISE_SIZE_2)
                .setLhsLocation(SDN_WISE_PACKET)
                .setLhs(SDN_WISE_DST_H)
                .setRhsLocation(SDN_WISE_CONST)
                .setRhs(this.addr.intValue()));
        toSink.addWindow(Window.fromString("P.TYPE > 127"));
        toSink.addAction(new ForwardUnicastAction()
                .setNextHop(addr));
        toSink.getStats().setPermanent();
        //log("initFlowTable - First Rule pos 0: " + toSink.toString());
        flowTable.add(0, toSink);

        for (int i = 1; i < SDN_WISE_RLS_MAX; i++) {
            flowTable.add(i, new FlowTableEntry());
        }
    }

    private void rxReport(ReportPacket packet) {
        controllerTX(packet);
    }

    private FunctionInterface createServiceInterface(byte[] classFile) {
        CustomClassLoader cl = new CustomClassLoader();
        FunctionInterface srvI = null;
        Class service = cl.defClass(classFile, classFile.length);
        try {
            srvI = (FunctionInterface) service.newInstance();
        } catch (InstantiationException | IllegalAccessException ex) {
            log(ex.getLocalizedMessage());
        }
        return srvI;
    }

    private void rxResponse(ResponsePacket rp) {
        if (isAcceptedIdPacket(rp)) {
            rp.getRule().setStats(new Stats());
            insertRule(rp.getRule(), searchRule(rp.getRule()));
        } else {
            runFlowMatch(rp);
        }
    }

    private void rxOpenPath(OpenPathPacket opp) {
        if (isAcceptedIdPacket(opp)) {
            List<NodeAddress> path = opp.getPath();
            //print path on console
            //log("Path: " + path);
            for (int i = 0; i < path.size(); i++) {
                NodeAddress actual = path.get(i);
                if (isAcceptedIdAddress(actual)) {
                    if (i != 0) {
                        FlowTableEntry rule = new FlowTableEntry();
                        rule.addWindow(new Window()
                                .setOperator(SDN_WISE_EQUAL)
                                .setSize(SDN_WISE_SIZE_2)
                                .setLhsLocation(SDN_WISE_PACKET)
                                .setLhs(SDN_WISE_DST_H)
                                .setRhsLocation(SDN_WISE_CONST)
                                .setRhs(path.get(0).intValue()));
                        rule.getWindows().addAll(opp.getOptionalWindows());
                        rule.addAction(new ForwardUnicastAction()
                                .setNextHop(path.get(i - 1))
                        );
                        int p = searchRule(rule);
                        insertRule(rule, p);
                    }

                    if (i != (path.size() - 1)) {
                        FlowTableEntry rule = new FlowTableEntry();
                        rule.addWindow(new Window()
                                .setOperator(SDN_WISE_EQUAL)
                                .setSize(SDN_WISE_SIZE_2)
                                .setLhsLocation(SDN_WISE_PACKET)
                                .setLhs(SDN_WISE_DST_H)
                                .setRhsLocation(SDN_WISE_CONST)
                                .setRhs(path.get(path.size() - 1).intValue()));

                        rule.getWindows().addAll(opp.getOptionalWindows());
                        rule.addAction(new ForwardUnicastAction()
                                .setNextHop(path.get(i + 1))
                        );

                        int p = searchRule(rule);
                        insertRule(rule, p);
                        opp.setDst(path.get(i + 1));
                        opp.setNxhop(path.get(i + 1));

                        radioTX(opp);
                        break;
                    }
                }
            }
        } else {
            runFlowMatch(opp);
        }
    }

    private void insertRule(FlowTableEntry rule, int pos) {
        if (pos >= SDN_WISE_RLS_MAX) {
            pos = flow_table_free_pos;
            flow_table_free_pos++;
            if (flow_table_free_pos >= SDN_WISE_RLS_MAX) {
                flow_table_free_pos = 1;
            }
        }
        log("Inserting rule " + rule + " at position " + pos);
        flowTable.set(pos, rule);
    }

    private int searchRule(FlowTableEntry rule) {
        int i, j, sum, target;
        for (i = 0; i < SDN_WISE_RLS_MAX; i++) {
            sum = 0;
            target = rule.getWindows().size();
            if (flowTable.get(i).getWindows().size() == target) {
                for (j = 0; j < rule.getWindows().size(); j++) {
                    if (flowTable.get(i).getWindows().get(j).equals(rule.getWindows().get(j))) {
                        sum++;
                    }
                }
            }
            if (sum == target) {
                return i;
            }
        }
        return SDN_WISE_RLS_MAX + 1;
    }

    private boolean isAcceptedIdAddress(NodeAddress addrP) {
        return (addrP.equals(addr)
                || addrP.isBroadcast()
                || (searchAcceptedId(addrP)
                != SDN_WISE_ACCEPTED_ID_MAX + 1));
    }

    private boolean isAcceptedIdPacket(NetworkPacket packet) {
        return isAcceptedIdAddress(packet.getDst());
    }

    private void rxHandler(NetworkPacket packet, int rssi) {
        
        if (packet.getLen() > SDN_WISE_DFLT_HDR_LEN
                && packet.getNetId() == net_id
                && packet.getTtl() != 0) {

            if (packet.isRequest()) {
                controllerTX(packet);
            } else {
                //log("Packet type: " + packet.getType());
                switch (packet.getType()) {
                    case SDN_WISE_DATA:
                        rxData(new DataPacket(packet));
                        break;

                    case SDN_WISE_BEACON:
                        rxBeacon(new BeaconPacket(packet), rssi);
                        break;

                    case SDN_WISE_REPORT:
                        rxReport(new ReportPacket(packet));
                        break;

                    case SDN_WISE_RESPONSE:
                        rxResponse(new ResponsePacket(packet));
                        break;

                    case SDN_WISE_OPEN_PATH:
                        rxOpenPath(new OpenPathPacket(packet));
                        break;

                    case SDN_WISE_CONFIG:
                        rxConfig(new ConfigPacket(packet));
                        break;

                    default:
                        runFlowMatch(packet);
                        break;
                }
            }
        }
    }

    private void initNeighborTable() {
        int i;
        for (i = 0; i < SDN_WISE_NEIGHBORS_MAX; i++) {
            neighborTable.add(i, new Neighbor());
        }
        neighbors_number = 0;
    }

    private void initStatusRegister() {
        for (int i = 0; i < SDN_WISE_STATUS_LEN; i++) {
            statusRegister.add(0);
        }
    }

    private void initAcceptedId() {
        for (int i = 0; i < SDN_WISE_ACCEPTED_ID_MAX; i++) {
            acceptedId.add(i, new NodeAddress(65535));
        }
    }

    private void setup() {

        addr = new NodeAddress(this.getID());
        net_id = (byte) 1;
        //log("Node " + addr.intValue() + " started");

        simulation = getSimulation();
        random = simulation.getRandomGenerator();
        radio = (ApplicationRadio) getInterfaces().getRadio();
        leds = (ApplicationLED) getInterfaces().getLED();
        MeasureLOGGER = Logger.getLogger("Measure" + addr.toString());
        MeasureLOGGER.setLevel(Level.parse("FINEST"));
        try {
            FileHandler fh;
            File dir = new File("logs");
            dir.mkdir();
            fh = new FileHandler("logs/Measures" + addr + ".log");
            fh.setFormatter(new SimplestFormatter());
            MeasureLOGGER.addHandler(fh);
            MeasureLOGGER.setUseParentHandlers(false);
        } catch (IOException | SecurityException ex) {
            log(ex.getLocalizedMessage());
        }
        neighborTable = new ArrayList<>(SDN_WISE_NEIGHBORS_MAX);
        acceptedId = new ArrayList<>(SDN_WISE_ACCEPTED_ID_MAX);
        flowTable = new ArrayList<>(50);

        initFlowTable();
        initNeighborTable();
        initAcceptedId();
        initStatusRegister();
        initSdnWise();

        new Thread(new PacketManager()).start();
        new Thread(new PacketSender()).start();
        new Thread(new MessageCreator()).start(); //gabrielgomes
        //new thread
        new Thread(new BatteryManager()).start(); 
        new Thread(new LogTest()).start(); 
    }

    private int getOperand(NetworkPacket packet, int size, int location, int value) {
        int[] intPacket = packet.toIntArray();
        switch (location) {
            case SDN_WISE_NULL:
                return 0;
            case SDN_WISE_CONST:
                return value;
            case SDN_WISE_PACKET:
                if (size == SDN_WISE_SIZE_1) {
                    if (value >= intPacket.length) {
                        return -1;
                    }
                    return intPacket[value];
                }
                if (size == SDN_WISE_SIZE_2) {
                    if (value + 1 >= intPacket.length) {
                        return -1;
                    }
                    return Utils.mergeBytes(intPacket[value], intPacket[value + 1]);
                }
            case SDN_WISE_STATUS:
                if (size == SDN_WISE_SIZE_1) {
                    if (value >= statusRegister.size()) {
                        return -1;
                    }
                    return statusRegister.get(value);
                }
                if (size == SDN_WISE_SIZE_2) {
                    if (value + 1 >= statusRegister.size()) {
                        return -1;
                    }
                    return Utils.mergeBytes(
                            statusRegister.get(value),
                            statusRegister.get(value + 1));
                }
        }
        return -1;
    }

	private int matchWindow(Window window, NetworkPacket packet) {
        int operator = window.getOperator();
        int size = window.getSize();
        int lhs = getOperand(
                packet, size, window.getLhsLocation(), window.getLhs());
        int rhs = getOperand(
                packet, size, window.getRhsLocation(), window.getRhs());
        return compare(operator, lhs, rhs);
    }

    private int matchRule(FlowTableEntry rule, NetworkPacket packet) {
        if (rule.getWindows().isEmpty()) {
            return 0;
        }

        int target = rule.getWindows().size();
        int actual = 0;

        for (Window w : rule.getWindows()) {
            actual = actual + matchWindow(w, packet);
        }
        return (actual == target ? 1 : 0);
    }

    private void runAction(AbstractAction action, NetworkPacket np) {
        try {
            int action_type = action.getType();
            //log("Action: " + action_type);

            switch (action_type) {
                case SDN_WISE_FORWARD_U:
                case SDN_WISE_FORWARD_B:
                    np.setNxhop(((AbstractForwardAction) action).getNextHop());
                    radioTX(np);
                    break;

                case SDN_WISE_DROP:
                    break;
                case SDN_WISE_SET:
                    SetAction ftam = (SetAction) action;
                    int operator = ftam.getOperator();
                    int lhs = getOperand(
                            np, SDN_WISE_SIZE_1, ftam.getLhsLocation(), ftam.getLhs());
                    int rhs = getOperand(
                            np, SDN_WISE_SIZE_1, ftam.getRhsLocation(), ftam.getRhs());
                    if (lhs == -1 || rhs == -1) {
                        throw new IllegalArgumentException("Operators out of bound");
                    }
                    int res = doOperation(operator, lhs, rhs);
                    if (ftam.getResLocation() == SDN_WISE_PACKET) {
                        int[] packet = np.toIntArray();
                        if (ftam.getRes() >= packet.length) {
                            throw new IllegalArgumentException("Result out of bound");
                        }
                        packet[ftam.getRes()] = res;
                        np.setArray(packet);
                    } else {
                        statusRegister.set(ftam.getRes(), res);
                        log("SET R." + ftam.getRes() + " = " + res + ". Done.");
                    }
                    break;
                case SDN_WISE_FUNCTION:
                    FunctionAction ftac = (FunctionAction) action;
                    FunctionInterface srvI = functions.get(ftac.getCallbackId());
                    if (srvI != null) {
                        log("Function called: " + addr);
                        srvI.function(adcRegister,
                                flowTable,
                                neighborTable,
                                statusRegister,
                                acceptedId,
                                flowTableQueue,
                                txQueue,
                                ftac.getArg0(),
                                ftac.getArg1(),
                                ftac.getArg2(),
                                np
                        );
                    }
                    break;
                case SDN_WISE_ASK:
                    np.setSrc(addr)
                            .setRequestFlag()
                            .setTtl(NetworkPacket.SDN_WISE_DFLT_TTL_MAX);
                    controllerTX(np);
                    break;
                case SDN_WISE_MATCH:
                    flowTableQueue.add(np);
                    break;
                case SDN_WISE_TO_UDP:
                    ToUdpAction tua = (ToUdpAction) action;
                    DatagramSocket sUDP = new DatagramSocket();
                    DatagramPacket pck = new DatagramPacket(np.toByteArray(),
                            np.getLen(), tua.getInetSocketAddress());
                    sUDP.send(pck);
                    break;
                default:
                    break;
            }//switch
        } catch (IOException ex) {
            log(ex.getLocalizedMessage());
        }
    }

    private int doOperation(int operatore, int item1, int item2) {
        switch (operatore) {
            case SDN_WISE_ADD:
                return item1 + item2;
            case SDN_WISE_SUB:
                return item1 - item2;
            case SDN_WISE_DIV:
                return item1 / item2;
            case SDN_WISE_MUL:
                return item1 * item2;
            case SDN_WISE_MOD:
                return item1 % item2;
            case SDN_WISE_AND:
                return item1 & item2;
            case SDN_WISE_OR:
                return item1 | item2;
            case SDN_WISE_XOR:
                return item1 ^ item2;
            default:
                return 0;
        }
    }

    private int compare(int operatore, int item1, int item2) {
        if (item1 == -1 || item2 == -1) {
            return 0;
        }
        switch (operatore) {
            case SDN_WISE_EQUAL:
                return item1 == item2 ? 1 : 0;
            case SDN_WISE_NOT_EQUAL:
                return item1 != item2 ? 1 : 0;
            case SDN_WISE_BIGGER:
                return item1 > item2 ? 1 : 0;
            case SDN_WISE_LESS:
                return item1 < item2 ? 1 : 0;
            case SDN_WISE_EQUAL_OR_BIGGER:
                return item1 >= item2 ? 1 : 0;
            case SDN_WISE_EQUAL_OR_LESS:
                return item1 <= item2 ? 1 : 0;
            default:
                return 0;
        }
    }

    void resetSemaphore() {
    }

    BeaconPacket prepareBeacon() {
        BeaconPacket bp = new BeaconPacket(
                net_id,
                addr,
                getActualSinkAddress(),
                distanceFromSink,
                battery.getBatteryPercent());
        return bp;
    }

    ReportPacket prepareReport() {

        ReportPacket rp = new ReportPacket(
                net_id,
                addr,
                getActualSinkAddress(),
                distanceFromSink,
                battery.getBatteryPercent());

        rp.setNeigh(neighbors_number)
                .setNxhop(getNextHopVsSink());

        for (int j = 0; j < neighbors_number; j++) {
            rp.setNeighbourAddressAt(neighborTable.get(j).getAddr(), j)
                    .setNeighbourWeightAt((byte) neighborTable.get(j).getRssi(), j);
        }
        initNeighborTable();
        return rp;
    }

    final void updateTable() {
        for (int i = 0; i < SDN_WISE_RLS_MAX; i++) {
            FlowTableEntry tmp = flowTable.get(i);
            if (tmp.getWindows().size() > 1) {
                int ttl = tmp.getStats().getTtl();
                if (ttl != SDN_WISE_RL_TTL_PERMANENT) {
                    if (ttl >= SDN_WISE_RL_TTL_DECR) {
                        //log("updateTable - Rule " + i + ":" + tmp.toString() + ". Decrementing TTL");
                        tmp.getStats().decrementTtl(SDN_WISE_RL_TTL_DECR);
                    } else {
                        flowTable.set(i, new FlowTableEntry());
                        log("Removing rule at position " + i);
                        if (i == 0) {
                            resetSemaphore();
                        }
                    }
                }
            }
        }
    }

    final int getNeighborIndex(NodeAddress addr) {
        int i;
        for (i = 0; i < SDN_WISE_NEIGHBORS_MAX; i++) {
            if (neighborTable.get(i).getAddr().equals(addr)) {
                return i;
            }
            if (neighborTable.get(i).getAddr().isBroadcast()) {
                return -1;
            }
        }
        return SDN_WISE_NEIGHBORS_MAX + 1;
    }

    final int searchAcceptedId(NodeAddress addr) {
        int i;
        for (i = 0; i < SDN_WISE_ACCEPTED_ID_MAX; i++) {
            if (acceptedId.get(i).equals(addr)) {
                return i;
            }
        }
        return SDN_WISE_ACCEPTED_ID_MAX + 1;
    }

    final int getActualFlowIndex(int j) {
        //j = j % SDN_WISE_RLS_MAX;
        int i;
        if (j == 0) {
            i = 0;
        } else {
            i = flow_table_free_pos - j;
            if (i == 0) {
                i = SDN_WISE_RLS_MAX - 1;
            } else if (i < 0) {
                i = SDN_WISE_RLS_MAX - 1 + i;
            }
        }
        return i;
    }

    void logTask() {
        //log("grafico");
        MeasureLOGGER.log(Level.FINEST,
                // NODE;BATTERY LVL(mC);BATTERY LVL(%);NO. RULES INSTALLED; B SENT; B RECEIVED;
                "{0},{1},{2},{3},{4},{5},{6},{7}",
                new Object[]{
                    addr,
                    String.valueOf(battery.getBatteryLevel()),
                    String.valueOf(battery.getBatteryPercent() / 2.55),
                    flow_table_free_pos,
                    sentBytes,
                    receivedBytes,
                    sentDataBytes,
                    receivedDataBytes
                });
    }

    private class CustomClassLoader extends ClassLoader {

        public Class defClass(byte[] data, int len) {
            return defineClass(null, data, 0, len);
        }
    }

    private class PacketManager implements Runnable {

        @Override
        public void run() {
            try {
                while (battery.getBatteryLevel() > 0) {
                    NetworkPacket tmpPacket = flowTableQueue.take();
                    battery.receiveRadio(tmpPacket.getLen());
                    receivedBytes += tmpPacket.getLen();
                    rxHandler(tmpPacket, 255);
                }
            } catch (InterruptedException ex) {
                log(ex.getLocalizedMessage());
            }
        }
    }

    private class PacketSender implements Runnable {

        @Override
        public void run() {
            try {
                while (true) {
                    NetworkPacket np = txQueue.take();
                    radioTX(np);
                }
            } catch (InterruptedException ex) {
                log(ex.getLocalizedMessage());
            }
        }
    }

    /**
     * Aqui a mensagem é criada
     * 
     * Trabalho original do aluno Gabriel Gomes
     * 
     * @author gabrielgomes
    */
    private class MessageCreator implements Runnable {

        @Override
        public void run() {
            try{
                Thread.sleep(60000);
                while (true) {
                    DataPacket p = new DataPacket(1,addr,getActualSinkAddress());
            
                    p.setSrc(addr)
                                    .setDst(getActualSinkAddress())
                                    .setTtl((byte) ttl_max);

                    String myAddr = addr.toString();

                    String aggMessage = myAddr;

                    for(byte[] message : CopyPacket) {
                        aggMessage = aggMessage + new String(message,Charset.forName("UTF-8"));
                    }

                    log("antes da agg: " + aggMessage);

                    //retorno da taxa de agregação
                    int output = ComputeOutputPayload();
                    
                    //criação da mensagem agragada com variação da taxa de agregação
                    //if (output == 1 && (agregada.length() < 7) == true) { //transformar ele em uma variavel    
                    
                    //log("mensagem: " + agregada);

                    if (output == 1 && (aggMessage.contains(";P")) == false) {

                        p.setPayload(("P " + addr + ";")
                            .getBytes(Charset.forName("UTF-8")));

                        log("depois da agg: " + 
                            new String(p.getPayload(),Charset.forName("UTF-8")));

                    } else {

                        String newAggMessage = "";

                        newAggMessage = aggMessage.substring(0, (output * 6)); //não usar o 6 e sim um delimitador

                        log("depois da aggr: " + newAggMessage);
                        //+ "origem da mensagem: " + p.getSrc() + "trafego da mensagem anterior" + count * texto);

                        p.setPayload((newAggMessage)
                            .getBytes(Charset.forName("UTF-8")));

                    }

                    //log(new String(p.getPayload(),Charset.forName("UTF-8")));
            
                    runFlowMatch(p);

                    CopyPacket.clear();

                    Thread.sleep(20000);
                }
            } catch (InterruptedException ex) {
                log(ex.getLocalizedMessage()); 
            } 
        }
    }
    
    /**
     * Insere payload no buffer de agregação
     * 
     *  Trabalho original do aluno Gabriel Gomes
     * 
     * @author gabrielgomes
    */
    public void CopyMessage(DataPacket p) {       	
        if(p.getDst() != addr){
            CopyPacket.add(p.getPayload());
	    }
        log("mensagem copiada: " + new String(p.getPayload(),Charset.forName("UTF-8")));
    }

    /**
     * Trabalho original do aluno Gabriel Gomes
     * 
     * @author gabrielgomes
    */
    public int ComputeOutputPayload() {
        int input;
        float rate = 1 - getRate();     //Math.round(1 - getRate());
        int output;
        String aggMessage = "";

        for(byte[] message : CopyPacket) {
            aggMessage = aggMessage + new String(message,Charset.forName("UTF-8"));
        }        

        String[] messageSplit = aggMessage.split(";");

        //O input ele pega a quantidade de elementos(pacotes) que se tem no vetor.
        //O acrescimo de +1 é por conta de o input não contar com o próprio pacote criado pelo nó
        input = (messageSplit.length) + 1; 
        
        output = Math.round(rate * input);

        if (output == 0 ){
            output++;
        }

        //log("output " + output);
        //log("input " + input);
        log("rate " + rate);

        return output;
    }

    /**
     * This Thread controls which node is rechargeable setting the hasHarvest variable.
     * It also controls the charging of the node. The thread sleeps for given minutes and
     * then calls the rechargeBattery method. To keep track of reading the Solar Trace values
     * it utilizes the rechargeStep and ciclePassed variable, incrementing them as need by
     * the requirments of the simulation.
     * 
     * hasHaverst is set in the isRechargeable() method. The nodes are user defined by text file.
     * (ids randomly selected by {@link}https://www.random.org/integer-sets/)
     * 
     * @author mjneto
    */
    private class BatteryManager implements Runnable {

        @Override
        public void run() {

            int advanceStep = 144;
            boolean hasHarvest;
            int rechargeStep = 0;
            int ciclePassed = 1;

            hasHarvest = isRechargeable();

            //log(String.valueOf(hasHarvest));

            try {
                //sleep for 30 minutes before starting the charging, then charge for 1 hour
                Thread.sleep(1800000);
                
                while (true) {
                    if(hasHarvest) {
                        if(rechargeStep <= 24) {
                            battery.rechargeBattery(ciclePassed, rechargeStep+advanceStep);
                            rechargeStep++;
                        } else {
                            break;
                        }
                    }
                    log("Time step: " + String.valueOf((rechargeStep-1) * 2.5) + " min elapsed (" + String.valueOf((rechargeStep+advanceStep)-1) +
                    ") / Day: " + String.valueOf(ciclePassed));
                    
                    //300000ms = 5m, 150000ms = 2.5m
                    Thread.sleep(150000);
                }
            } catch (InterruptedException ex) {
                log(ex.getLocalizedMessage());
            }
        }

        public boolean isRechargeable() {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("../examples/sdn-wise_java/rechargeableNodes.txt"));
                String line = reader.readLine();
                reader.close();

                String[] rechargeableNodes = line.split(" ");
                //log("Node" + addr.intValue());
                if(Arrays.asList(rechargeableNodes).contains(String.valueOf(addr.intValue()))) {
                    //log("Node " + addr.intValue() + " is rechargeable");
                    return true;
                }
            } catch (Exception e) {
                log(e.getLocalizedMessage());
            }
            return false;
        }
    }

    /**
     * A test thread who gets the the Battery level and and simulation time
     * every 5 minutes and print it to the console.
     * 
     * Just for testing purposes.
     */

    private class LogTest implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    //Thread sleep for 5m and 200ms to avoid the simulation time to be the same and overlap
                    Thread.sleep(200);
                    log("Battery:" + String.valueOf(battery.getBatteryPercent() / 2.55) +
                    "\t" + "TimeSimulation:" + String.valueOf(simulation.getSimulationTimeMillis() / 1000) +
                    "\t" + "Rate:" + String.valueOf(1-getRate()) +
                    "\t" + "BatteryLevel:" + String.valueOf(battery.getBatteryLevel()));
                    Thread.sleep(300000);
                }
            } catch (InterruptedException ex) {
                log(ex.getLocalizedMessage());
            }
        }
    }
}
