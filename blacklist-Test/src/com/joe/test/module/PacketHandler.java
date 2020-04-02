package com.joe.test.module;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PacketHandler class is responsible for parsing PacketIn messages.
 */
public class PacketHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketHandler.class);

    public static final short   TYPE_IPv4 = 0x0800;
    public static final short   TYPE_8021Q = (short) 0x8100;

    private final IOFSwitch         mOfSwitch;
    private final OFPacketIn        mPacketIn;
    private final FloodlightContext mContext;
    private       boolean           isDnsPacket;

    //---------------------------------------------------------------------------------------------
    //  PacketHandler:  constructor
    public PacketHandler( final IOFSwitch ofSwitch, final OFMessage msg, final FloodlightContext context )
    {
        mOfSwitch   = ofSwitch;
        mPacketIn   = (OFPacketIn) msg;
        mContext    = context;
        isDnsPacket = false;
    }

    //---------------------------------------------------------------------------------------------
    //  processPacket:  process the incoming packet that has been forwarded to the controller.  We will:
    //         - Look for known blacklisted IP addresses, and drop packet if found
    //         - Look for known blacklisted hostnames, and drop packet if found
    public Command processPacket()
    {
        //---- First, get the OFMatch object from the incoming packet
        final OFMatch ofMatch = new OFMatch();
        ofMatch.loadFromPacket( mPacketIn.getPacketData(), mPacketIn.getInPort() );

        //---- If the packet isn't IPv4, we don't care about it, so ignore.
        if( ofMatch.getDataLayerType() != Ethernet.TYPE_IPv4 )
        {
            LOG.trace("Non IPv4 packet.");
            return Command.CONTINUE;
        }

        //---- We have an IPv4 packet, so check the destination IPv4 address against IPv4 blacklist.
        try
        {
            InetAddress ipAddr = InetAddress.getByAddress( IPv4.toIPv4AddressBytes( ofMatch.getNetworkDestination() ) );
            if (BlacklistMgr.getInstance().checkIpv4Blacklist( ipAddr ) )  // Is it on the blacklist?
            {
                //---- It's on the blacklist, so log the fact that we caught it, and drop the packet so it doesn't get resolved by DNS.
                StatisticsMgr.getInstance().updateIpv4Stats( mOfSwitch, ofMatch, ipAddr );
                LOG.info( "IPv4 packet to {} dropped.", ipAddr.getHostAddress() );

                FlowMgr.getInstance().dropPacket( mOfSwitch, mContext, mPacketIn );  // Drop the packet and stop processing.
                return Command.STOP;
            }

        }
        catch( UnknownHostException e1 )
        {
            LOG.error( "Unable to get destination IPv4 address from PacketIn {}", mPacketIn );
            e1.printStackTrace();

            return Command.CONTINUE;
        }

        //---- If the packet is a DNS query, check to see if the hostname to lookup is on the DNS blacklist
        if( ofMatch.getNetworkProtocol() == IPv4.PROTOCOL_UDP && ofMatch.getTransportDestination() == FlowMgr.DNS_QUERY_DEST_PORT )
        {
            final byte[] pkt = mPacketIn.getPacketData();
            Collection<String> domainNames;

            isDnsPacket = true;

            //---- Get domain names from DNS request (return if we encounter an error in parsing the packet).
            try
            {
                domainNames = parseDnsPacket( pkt );
            }
            catch( IOException e )  // Got here if there was an exception in parsing the domain names in the DNS request.
            {
                LOG.error( "Unable to parse DNS query packet {}", mPacketIn );
                e.printStackTrace();

                return Command.CONTINUE;
            }

            //---- If there were no domain names in the request, just let it go through.
            if( domainNames == null ) {
                forwardPacket();
                return Command.STOP;
            }

            //---- Process all the domain names from the request
            for( String domainName : domainNames )
            {
                //  If the current domainName is in the blacklist, take action immediately
                if( BlacklistMgr.getInstance().checkDnsBlacklist( domainName ) )
                {
                    StatisticsMgr.getInstance().updateDnsStats( mOfSwitch, ofMatch, domainName );
                    LOG.info( "DNS query packet dropped. Domain name: {}", domainName );

                    FlowMgr.getInstance().dropPacket( mOfSwitch, mContext, mPacketIn );  // Drop the packet
                    return Command.STOP;  // Note that we are dropping the whole DNS packet, even if only one hostname is bad.
                }
            }
        }

        forwardPacket();      // Allow packet to be forwarded, and allow all traffic to this destination.
        return Command.STOP;  // Done processing this packet.
    }

    //---------------------------------------------------------------------------------------------
    //  Utility method to cause the packet to be sent normally (forward), and flow added.
    //
    private void forwardPacket()
    {
        //---- Get the output port for this destination IP address.
        short outputPort = FlowMgr.getInstance().getOutputPort( mOfSwitch, mContext, mPacketIn );

        //---- If we can't get a valid output port for this destination IP address, we have to drop it.
        if( outputPort == OFPort.OFPP_NONE.getValue() ) FlowMgr.getInstance().dropPacket( mOfSwitch, mContext, mPacketIn );

        //---- Else if we should flood the packet, do so.
        else if( outputPort == OFPort.OFPP_FLOOD.getValue() ) {
            FlowMgr.getInstance().floodPacket( mOfSwitch, mContext, mPacketIn );
        }

        //---- Else we have a port to send this packet out on, so do it.
        else
        {
            final List<OFAction> actions = new ArrayList<OFAction>();
            actions.add( new OFActionOutput(outputPort) );

            LOG.info( "--[PacketHandler forwardPacket] sending packet out port:{}", outputPort );

            //--- Note that for DNS requests, we don't want to set flows up on the switch.
            if( !isDnsPacket ) FlowMgr.getInstance().createDataStreamFlow( mOfSwitch, mContext, mPacketIn, actions );

            //--- Forward the packet in both cases.
            FlowMgr.getInstance().sendPacketOut( mOfSwitch, mContext, mPacketIn, actions );

        }

    }

    //---------------------------------------------------------------------------------------------
    //  parseDnsPacket:  Called to parse the DNS request and extract and return the domain names
    //
    private Collection<String> parseDnsPacket(byte[] pkt) throws IOException
    {

        final DataInputStream packetDataInputStream = new DataInputStream(new ByteArrayInputStream(pkt));
        int position = 0;

        /* Parse input packet */
        // Skip Ethernet header: dst(6) and source(6) MAC, DataLayer type(2),
        packetDataInputStream.skip(6 + 6);
        short etherType = packetDataInputStream.readShort();
        position += 14;

        // Skip VLAN tags
        while (etherType == TYPE_8021Q)
        {
            @SuppressWarnings("unused")
            final short vlanId = packetDataInputStream.readShort();
            etherType = packetDataInputStream.readShort();
            position += 4;
        }

        if (etherType != TYPE_IPv4)
        {
            LOG.error("Unknown etherType. " + String.format("%04X ", etherType));
            return null;
        }

        // Parse IPv4 header
        final byte ipByte1 = packetDataInputStream.readByte();
        position += 1;
        final int version = (ipByte1 & 0xF0) >> 4;
        final int IHL = ipByte1 & 0x0F; // length in number of words
        //Check version
        if (version != 4)
        {
            LOG.error("Packet IP unknown version");
            return null;
        }
        //Check IP header length
        if (IHL < 5)
        {
            LOG.error("Packet IP header too small");
            return null;
        }
        // Skip IPv4 header
        packetDataInputStream.skip(IHL * 4 - 1);
        position += IHL * 4 - 1;

        // Parse UDP packet
        // Skip source port (2), destination port (2), length (2), checksum (2)
        // and query ID (2)
        packetDataInputStream.skip(2 + 2 + 2 + 2 + 2);
        position += 10;

        // read query flags, check QR bit (Query/Response)
        final short DNS_QR_BIT_NUMBER = (short) 15;
        if (((packetDataInputStream.readShort() >> DNS_QR_BIT_NUMBER) & 1) == 0)
        {
            position += 2;

            // Parse DNS query data, save domain names in String Collection
            Collection<String> domainNames = new ArrayList<String>();

            // Read number of queries
            int numQueries = packetDataInputStream.readShort();
            position += 2;

            // Skip numAnswers (2), numAuthorities (2), numAdditional (2);
            packetDataInputStream.skip(6);
            position += 6;

            // read queries
            for (int i = 0; i < numQueries; i++)
            {
                String dName =
                    DNSQueryQuestionParser.getDomainName(pkt, position);
                domainNames.add(dName);

                // Skip queryType (2), queryClass (2)
                packetDataInputStream.skip(4);
                position += 4;
            }

            if (domainNames.size() == 0)
            {
                LOG.error("Unable to parse domain question(s)");
                domainNames = null;
            }

            return domainNames;
        }

        return null;
    }

    //---------------------------------------------------------------------------------------------
    /**
     * Class DNSQueryQuestionParser is responsible for parsing domain name
     * questions in DNS query packet.
     *
     * DNS query question parer code is based on class
     * org.apache.directory.server.dns.io.decoder.DnsMessageDecoder developed
     * for Apache DNS server project.
     *
     *  Licensed to the Apache Software Foundation (ASF) under one
     *  or more contributor license agreements.  See the NOTICE file
     *  distributed with this work for additional information
     *  regarding copyright ownership.  The ASF licenses this file
     *  to you under the Apache License, Version 2.0 (the
     *  "License"); you may not use this file except in compliance
     *  with the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     *  Unless required by applicable law or agreed to in writing,
     *  software distributed under the License is distributed on an
     *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
     *  KIND, either express or implied.  See the License for the
     *  specific language governing permissions and limitations
     *  under the License.
     *
     */
    private static class DNSQueryQuestionParser
    {

        static String getDomainName(byte[] buf, int pos) throws IOException
        {
            StringBuffer domainName = new StringBuffer();
            recurseDomainName(buf, pos, domainName);

            return domainName.toString();
        }

        static void recurseDomainName( byte[] pkt, int pos, StringBuffer domainName ) throws IOException
        {
            final DataInputStream byteBuffer = new DataInputStream( new ByteArrayInputStream(pkt, pos, pkt.length - pos) );
            int length = byteBuffer.readUnsignedByte();

            if( ( length & 0xc0 ) == 0xc0 )
            {
                int position = byteBuffer.readUnsignedShort();
                int offset   = length & ~(0xc0) << 8;

                recurseDomainName( pkt, position + offset, domainName );
            }
            else if (length != 0 && (length & 0xc0) == 0)
            {
                int labelLength = length;

                /* read label */
                byte[] strBytes = new byte[labelLength];
                byteBuffer.readFully(strBytes);
                String label = new String(strBytes);
                domainName.append(label);
                if (byteBuffer.readByte() != 0)
                {
                    domainName.append(".");
                    recurseDomainName(pkt, pos + labelLength + 1, domainName);
                }
            }
        }

    }

}
