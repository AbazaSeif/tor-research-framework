package tor.examples;

import org.bouncycastle.util.encoders.Base64;
import tor.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/**
 * Created by gho on 27/06/14.
 */
public class SOCKSProxy {
    // socks client class
    class SocksClient implements TorStream.TorStreamListener{
        SocketChannel client;
        boolean connected;
        long lastData = 0;
        TorStream stream;
        TorCircuit circ;
        InetAddress remoteAddr;
        int port;

        SocksClient(SocketChannel c, TorCircuit circ) throws IOException {
            client = c;
            client.configureBlocking(false);
            lastData = System.currentTimeMillis();
            this.circ = circ;
        }

        public void newClientData(Selector selector, SelectionKey sk) throws IOException {
            if(!connected) {
                ByteBuffer inbuf = ByteBuffer.allocate(512);
                if(client.read(inbuf)<1)
                    return;
                inbuf.flip();
                //inbufinbufinbufinb.get() final DataInputStream in = new DataInputStream(Channels.newInputStream(client));
//                final DataOutputStream out = new DataOutputStream(Channels.newOutputStream(client));

                // read socks header
                int ver = inbuf.get();
                if (ver != 4) {
                    throw new IOException("incorrect version" + ver);
                }
                int cmd = inbuf.get();

                // check supported command
                if (cmd != 1) {
                    throw new IOException("incorrect version");
                }

                port = inbuf.getShort();

                final byte ip[] = new byte[4];
                // fetch IP
                inbuf.get(ip);

                remoteAddr = InetAddress.getByAddress(ip);

                while ((inbuf.get()) != 0) ; // username

                // hostname provided, not IP
                if (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) { // host provided
                    String host = "";
                    byte b;
                    while ((b = inbuf.get()) != 0) {
                        host += b;
                    }
                    remoteAddr = InetAddress.getByName(host);
                    System.out.println(host + remoteAddr);
                }

                stream = circ.createStream(remoteAddr.getHostAddress(), port, this);
            } else {
                ByteBuffer buf = ByteBuffer.allocate(4096);
                int nlen = 0;
                if((nlen = client.read(buf)) == -1)
                    throw new IOException("disconnected");
                lastData = System.currentTimeMillis();
                buf.flip();
                byte b[] = new byte[nlen];
                buf.get(b);
                stream.send(b);
            }
        }

        @Override
        public void dataArrived(TorStream s) {
            try {
                if(!client.isConnected())
                    removeClient(this);

                client.write(ByteBuffer.wrap(s.recv(-1, false)));
            } catch (IOException e) {
                try {
                    removeClient(this);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                e.printStackTrace();
            }

        }

        @Override
        public void connected(TorStream s) {
            ByteBuffer out = ByteBuffer.allocate(20);
            out.put((byte)0);
            out.put((byte) (0x5a));
            out.putShort((short) port);
            out.put(remoteAddr.getAddress());
            out.flip();
            try {
                client.write(out);
            } catch (IOException e) {
                try {
                    removeClient(this);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                System.out.println(e);
            }

            connected = true;
        }

        @Override
        public void disconnected(TorStream s) {
            try {
                removeClient(this);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void failure(TorStream s) {
            disconnected(s);
            try {
                removeClient(this);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    static ArrayList <SocksClient> clients = new ArrayList<SocksClient>();

    // utility function
    public SocksClient addClient(SocketChannel s, TorCircuit circ) {
        SocksClient cl;
        try {
            cl = new SocksClient(s, circ);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        clients.add(cl);
        return cl;
    }

    public void removeClient(SocksClient c) throws IOException {
        c.client.close();
        c.stream.destroy();
        clients.remove(c);
    }

    public SOCKSProxy() throws IOException {
        OnionRouter local = new OnionRouter("nas", "5C493EC1D035322D6575A84F040687EC5D2FA241", "192.168.0.8", 8001, 0) {
            @Override
            public PublicKey getPubKey() throws IOException {
                return TorCrypto.asn1GetPublicKey(Base64.decode("MIGJAoGBAMTF1X28OmCN+gt7fwRiL9fI/hd3nKdAN/sBXOrDAB/A9CW/Dd2avqeX\n" +
                        "ZKarmW3HbVZAdTGECu39p9h6lf5NHbLR2ZSDghcP5qb9m4ZsNg+PeLwu7M5cYRnR\n" +
                        "GTHIh8ybRpGGtoCoL+mVF8MCNSfELCXQ9S3YTzqN/IzyrM3+lt0HAgMBAAE="));
            }
        };

        OnionRouter guard = TorSocket.getConsensus().getRouterByName("southsea0");
        TorSocket sock = new TorSocket(guard);

        // connected---------------
        TorCircuit circ = sock.createCircuit();
        circ.createRoute("gho,IPredator");
        //circ.create(local);
        circ.waitForState(TorCircuit.STATES.READY);

        System.out.println("READY!!");

        ServerSocketChannel socks = ServerSocketChannel.open();
        socks.socket().bind(new InetSocketAddress(9050));
        socks.configureBlocking(false);
        Selector select = Selector.open();
        socks.register(select, SelectionKey.OP_ACCEPT);

        int lastClients = clients.size();
        // select loop
        while(true) {
            select.select(1000);

            Set keys = select.selectedKeys();
            Iterator iterator = keys.iterator();
            while (iterator.hasNext()) {
                SelectionKey k = (SelectionKey) iterator.next();

                if (!k.isValid())
                    continue;

                // new connection?
                if (k.isAcceptable() && k.channel() == socks) {
                    // server socket
                    SocketChannel csock = socks.accept();
                    if (csock == null)
                        continue;
                    addClient(csock, circ);
                    csock.register(select, SelectionKey.OP_READ);
                } else if (k.isReadable()) {
                    // new data on a client/remote socket
                    for (int i = 0; i < clients.size(); i++) {
                        SocksClient cl = clients.get(i);
                        try {
                            if (k.channel() == cl.client) // from client (e.g. socks client)
                                cl.newClientData(select, k);
                        } catch (IOException e) { // error occurred - remove client
                            cl.client.close();
                            k.cancel();
                            clients.remove(cl);
                        }

                    }
                }
            }

            // client timeout check
            for (int i = 0; i < clients.size(); i++) {
                SocksClient cl = clients.get(i);
                if((System.currentTimeMillis() - cl.lastData) > 30000L) {
                    cl.stream.destroy();
                    cl.client.close();
                    clients.remove(cl);
                }
            }
            if(clients.size() != lastClients) {
                System.out.println(clients.size());
                lastClients = clients.size();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new SOCKSProxy();
    }
}
