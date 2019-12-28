package chatClient;

import static com.sun.corba.se.impl.orbutil.ORBUtility.bytesToInt;
import com.sun.corba.se.spi.legacy.connection.GetEndPointInfoAgainException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.net.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

public class client_frame extends javax.swing.JFrame 
{
    final int MAXPACKET = 4096;
    final int HEADER = 16;
    String username;
    ArrayList<String> users = new ArrayList();
    Boolean isConnected = false;
    long fd;
    Socket sock;
    BufferedReader reader;
    PrintWriter writer;
    int port;
    String address;
    private volatile boolean shutdownRequested = false;
    private volatile boolean receivedAck = false;
    Map<String,BlockingDeque<byte[]>> workQ = new HashMap<String,BlockingDeque<byte[]>>();
    BlockingDeque<byte[]> work_queue;
    
    //--------------------------//
    public synchronized void setShutdown(boolean set){
        shutdownRequested=set;
    }
        //--------------------------//

    public synchronized boolean getShutdown(){
         return shutdownRequested;
    }
        //--------------------------//

    
    public synchronized boolean getReceivedAck(){
        return receivedAck;
    }
        //--------------------------//

    
    public synchronized void setReceivedAck(boolean set){
        receivedAck=set;
    }
        //--------------------------//

     
    public Thread SocketThread(String address,int port,int bufSize) 
    {
        Boolean stop = false;
        work_queue = new LinkedBlockingDeque<byte[]>(); //threadSafe
        Thread SendThread = new Thread(new SendPackets(address, port, bufSize,work_queue));
        SendThread.start();
        //Thread AckThread = new Thread(new AckReceive(socket,bufSize));
        //AckThread.start();
        return SendThread;
    }
    
    //--------------------------//
     
    
    public client_frame() 
    {
        initComponents();
    }
        //--------------------------//


    private long netfifo_snd_open(String address, int port, int bufSize) {
        
        try
        {
            
            int size = bufSize;
            Boolean stop = false;
            work_queue = new LinkedBlockingDeque<byte[]>(); //threadSafe
            Thread SendThread = new Thread(new SendPackets(address, port, bufSize,work_queue));
            SendThread.start();
            workQ.put(String.valueOf(SendThread.getId()), work_queue);
            
            //Thread AckThread = new Thread(new AckReceive(socket,bufSize));
            //AckThread.start();
            return SendThread.getId();
        }
            catch (Exception ex) 
            {
                ta_chat.append("Cannot create fifo with "+address+"@"+ port + "! Try Again. \n");
                ex.printStackTrace();
            }
        
        //Cannot create fifo ,return -1
        return -1;
    }
        //--------------------------//

    
    
    private int netfifo_write(long fd, byte[] buffer, int len){
        
        byte[] temp = new byte[len];
        System.arraycopy(buffer, 0, temp, 0, len);
        (workQ.get(String.valueOf(fd))).offer(temp);
        return len;
    }
        //--------------------------//


    private void netfifo_snd_close(long fd) {
        
        Thread worker = findThreadById(fd);
        if(worker.isAlive() /*&& !workQ.get(String.valueOf(fd)).isEmpty()*/){
            int reply = JOptionPane.showConfirmDialog(null, "Want to end all?", "Title", JOptionPane.YES_NO_OPTION);
            if (reply == JOptionPane.YES_OPTION) {
                worker.interrupt();
                workQ.remove(String.valueOf(fd));
            }
        }
        

    }
        //--------------------------//

    
    private static ThreadGroup getRootThreadGroup(Thread thread) {
        ThreadGroup rootGroup = thread.getThreadGroup();
        while (true) {
            ThreadGroup parentGroup = rootGroup.getParent();
            if (parentGroup == null) {
                break;
            }
            rootGroup = parentGroup;
        }
        return rootGroup;
    }
        //--------------------------//

    
    public int getTotalBytes(ArrayList<byte[]> arr){
        int sum=0;
        Iterator<byte[]> it = arr.iterator();
        while(it.hasNext()){
            sum+=it.next().length;
        }
        return sum;
    }
        //--------------------------//

    
    
    
    public synchronized boolean isAcked(ArrayList<byte[]> work,int id,int packetN){
        if(work.get(id)[packetN*(MAXPACKET-HEADER+1)]==0b0001){
            return true;
        }else{
            return false;
        }
    }
        //--------------------------//

    
    public synchronized void Ack(ArrayList<byte[]> work,int id,int packetN){
        work.get(id)[packetN*(MAXPACKET-HEADER+1)]=0b0001;
    }
        //--------------------------//

    
    public int getMax(int desired, int max){
        if(desired<max){
            return desired;
        }else{
            return max;
        }
        
    }
        //--------------------------//

    
    public Thread findThreadById(long id){
        //Give you set of Threads
        Set<Thread> setOfThread = Thread.getAllStackTraces().keySet();

        //Iterate over set to find yours
        for(Thread thread : setOfThread){
            if(thread.getId()==id){
                return thread;
            }
        }
        return null;
    }
        //--------------------------//

    
    public byte[] toByteArray(int value) {
        return new byte[] { 
            (byte)(value >> 24),
            (byte)(value >> 16),
            (byte)(value >> 8 ),
            (byte)(value      )};
    }
        //--------------------------//

    
    public int fromByteArray(byte[] bytes,int index) {
        return bytes[index + 0] << 24 | (bytes[index +1] & 0xFF) << 16 | (bytes[index +2] & 0xFF) << 8 | (bytes[index +3] & 0xFF);
    }
    
    //--------------------------//
    
    private class SendPackets implements Runnable
    {
        byte[] buffer;
        byte[] packet;
        volatile ArrayList<byte[]> works = new ArrayList<>();
        byte[] newWork;
        int[] status;
        DatagramSocket socket;
        int bufSize;
        int remainBuf;
        int pos;
        int nWorkSize;
        int length=4;
        int port;
        InetAddress address;
        boolean received=false;
        DatagramPacket serverIn ;
        BlockingDeque<byte[]> workLoad;
        
        public SendPackets(String address, int port, int bufSize,BlockingDeque workLoad){
            try {
                this.workLoad=workLoad;
                this.address = InetAddress.getByName(address);
                this.port = port;
                
                this.socket = new DatagramSocket();
                //this.serverIn = new DatagramPacket(buffer, buffer.length);
                this.status = new int[bufSize];
                this.bufSize = bufSize;
                this.pos=0;
                
                
                Thread AckThread = new Thread(new AckReceive(this.socket,bufSize,works));
                AckThread.start();
            } catch (SocketException ex) {
                Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
            } catch (UnknownHostException ex) {
                Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        @Override
        public void run() 
        {
            while (!Thread.interrupted()) {
                
                    try {
                        remainBuf=bufSize-getTotalBytes(works);
                        if(!workLoad.isEmpty()){
                            nWorkSize=workLoad.peek().length;
                        /*can we take in more work to send?*/
                            if( nWorkSize<= remainBuf){

                                newWork = workLoad.take();
                                int numPackets = (int)Math.ceil(((newWork.length)/MAXPACKET)+1);
                                buffer = new byte[newWork.length+numPackets];
                                System.out.println("@~~~"+newWork.length +":"+numPackets);
                                System.out.println("#~~~"+buffer.length);

                                for(int i=0;i<numPackets;i++){

                                    byte[] temp = new byte[]{
                                        (0b0)
                                    };
                                    System.arraycopy(temp, 0, buffer, i+(i*(MAXPACKET-HEADER)), 1);
                                    System.out.println(i+": package with :"+((i*(MAXPACKET-HEADER+1))+1));
                                    
                                    
                                    int index =  getMax(buffer.length-2-(i*(MAXPACKET-HEADER)), MAXPACKET-HEADER);
                                    System.out.println("bytes to write :"+index);
                                    
                                    
                                    System.out.println("Copy from newWork["+(i*(MAXPACKET-HEADER))+"] to buffer["+((i*(MAXPACKET-HEADER+1))+1)+"] "+ getMax(buffer.length-1-(i*(MAXPACKET-HEADER)), MAXPACKET-HEADER)+" bytes");
                                    System.arraycopy(newWork, (i*(MAXPACKET-HEADER)), buffer, (i*(MAXPACKET-HEADER+1))+1,  getMax(buffer.length-2-(i*(MAXPACKET-HEADER)), MAXPACKET-HEADER));

                                    System.out.println("!"+new String(buffer,"utf-8")+"!");
                                }
                                works.add(buffer);


                            }else{
                                System.out.println("Cant take more work to do,new workload cant fit in buffer");
                            }
                                 
                        }
                        
                        if(works.size()>0){
                            /*in each loop we send the packages of a whole reqquest,except the ones that have been ack'ed*/
                            int numPackets = (int)Math.ceil(((works.get(pos).length)/MAXPACKET)+1);
                            System.out.println("~~~" + pos+" numpackets "+numPackets);

                            int status=0;
                            for(int z=0;z<numPackets;z++){
                                if(isAcked(works, pos, z)){
                                    status++;
                                    if(status==numPackets){
                                        works.remove(pos);
                                    }
                                }else{
                                    int packetSize;
                                    if(z+1 != numPackets){
                                        packetSize = MAXPACKET-HEADER;
                                    }else{
                                        packetSize = works.get(pos).length - (z*(MAXPACKET-HEADER+1)) - 1;
                                    }
                                    //SEND packet

                                    System.out.println("z: "+z+" "+packetSize);
                                    /*--PREPARE PACKET*/
                                    packet = new byte[HEADER+packetSize];
                                    System.arraycopy(ByteBuffer.allocate(4).putInt(pos).array(), 0, packet, 0, 4); //id
                                    System.arraycopy(ByteBuffer.allocate(4).putInt(packetSize).array(), 0, packet, 4, 4); //this packetSize(without header)
                                    System.arraycopy(ByteBuffer.allocate(4).putInt(z).array(), 0, packet, 8, 4); //number of this packet
                                    System.arraycopy(ByteBuffer.allocate(4).putInt(numPackets).array(), 0, packet, 12, 4); //total number of packets for this 'id'
                                    //data
                                    System.arraycopy(works.get(pos), (z*(MAXPACKET-HEADER+1))+1, packet,HEADER, packetSize);

                                    
                                    //works.get(pos)[z*(MAXPACKET-header+1)]=0b0001;
                                    /*---SEND IT---*/
                                    
                                    DatagramPacket packetOut = new DatagramPacket(packet, packet.length, address, port);
                                    System.out.println("sending to : " + address + " with port : " + port );
                                    socket.send(packetOut);
                                    ta_chat.append("Package " + z +" sent,waiting for ack...\n");
                                    ta_chat.setCaretPosition(ta_chat.getText().length());
                                    
                                }


                            }
                            Thread.sleep(1000);
                            if(!works.isEmpty()){
                                pos=(pos+1)%works.size();
                            }
                            setReceivedAck(false);
                        }
                        /*status[pos]=0;
                        pos=(pos+1)%status.length;*/
                    } catch (InterruptedException ex) { 
                        //Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
                        break;
                    } catch (UnknownHostException ex ) { 
                        Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
                        break;
                    } catch (IOException ex) { 
                        Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
                    } 
                    
            }
            setShutdown(true);
            
        }
        
    }
    
    private class AckReceive implements Runnable
    {
        
        byte[] buffer;
        int bufSize;
        int header=16;
        DatagramSocket socket;
        DatagramPacket serverIn;
        ArrayList<byte[]> work;

        private AckReceive(DatagramSocket socket, int bufSize,ArrayList<byte[]> work) {
            this.socket = socket;
            this.buffer = new byte[bufSize];
            this.work = work;
            serverIn = new DatagramPacket(buffer, buffer.length);
        }
        
        @Override
        public void run() 
        {
            
            while(!getShutdown()){
                
                try {
                    socket.receive(serverIn);
                    System.out.println("Ack OK received");
                    
                    String rcvd = new String(serverIn.getData(), 0, serverIn.getLength()) + ", from address: " + serverIn.getAddress() + ", port: " + serverIn.getPort();
                    int id=fromByteArray(serverIn.getData(),0);
                    int packetSise=fromByteArray(serverIn.getData(),4);
                    int numberOfPacket=fromByteArray(serverIn.getData(),8);
                    int totalPackets=fromByteArray(serverIn.getData(),12);
                    ta_chat.append("Ack received:\n\tid:"+  id +"\n\tpacketSize:"+packetSise+"\n\tnumberofPacket:"+numberOfPacket+"\n\ttotalPackets:"+totalPackets+" \n");
                    
                    Ack(work, id, numberOfPacket);
                    //work.get(id)[numberOfPacket*(MAXPACKET-header+1)]=0b0001;
                                    
                    ta_chat.setCaretPosition(ta_chat.getText().length());
                    
                } catch (IOException ex) {
                    System.out.println("Package dropped");
                    Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
                    
                }
                //System.out.println("~ACK-service RUNNING");
            }
            System.out.println("~ACK-service STOPPED");
        }
    }

    //--------------------------//
    
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        lb_address = new javax.swing.JLabel();
        lb_port = new javax.swing.JLabel();
        b_connect = new javax.swing.JButton();
        b_disconnect = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        ta_chat = new javax.swing.JTextArea();
        tf_chat = new javax.swing.JTextField();
        b_send = new javax.swing.JButton();
        tf_address = new javax.swing.JFormattedTextField();
        tf_port = new javax.swing.JFormattedTextField();
        jButton1 = new javax.swing.JButton();
        jButton2 = new javax.swing.JButton();
        jTextField1 = new javax.swing.JTextField();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Chat - Client's frame");
        setLocationByPlatform(true);
        setName("client"); // NOI18N
        setResizable(false);

        lb_address.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lb_address.setText("Address : ");

        lb_port.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lb_port.setText("Port :");

        b_connect.setText("Connect");
        b_connect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                b_connectActionPerformed(evt);
            }
        });

        b_disconnect.setText("Disconnect");
        b_disconnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                b_disconnectActionPerformed(evt);
            }
        });

        ta_chat.setEditable(false);
        ta_chat.setColumns(20);
        ta_chat.setRows(5);
        jScrollPane1.setViewportView(ta_chat);

        tf_chat.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                tf_chatActionPerformed(evt);
            }
        });

        b_send.setText("SEND");
        b_send.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                b_sendActionPerformed(evt);
            }
        });

        tf_address.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        tf_address.setText("localhost");
        tf_address.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                tf_addressActionPerformed(evt);
            }
        });
        tf_address.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyReleased(java.awt.event.KeyEvent evt) {
                tf_addressKeyReleased(evt);
            }
        });

        tf_port.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        tf_port.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        tf_port.setText("8888");
        tf_port.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyReleased(java.awt.event.KeyEvent evt) {
                tf_portKeyReleased(evt);
            }
        });

        jButton1.setText("Clear");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });

        jButton2.setText("Send File...");
        jButton2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton2ActionPerformed(evt);
            }
        });

        jTextField1.setText("jTextField1");
        jTextField1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextField1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lb_address, javax.swing.GroupLayout.PREFERRED_SIZE, 62, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(tf_address)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(lb_port, javax.swing.GroupLayout.PREFERRED_SIZE, 56, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(tf_port, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(b_connect)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(b_disconnect))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(tf_chat, javax.swing.GroupLayout.PREFERRED_SIZE, 352, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jButton2)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jTextField1)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(b_send, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jButton1, javax.swing.GroupLayout.PREFERRED_SIZE, 164, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(lb_address)
                        .addComponent(b_disconnect)
                        .addComponent(b_connect)
                        .addComponent(tf_address, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(tf_port, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(lb_port, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(44, 44, 44)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 270, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(b_send, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButton1))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(tf_chat, javax.swing.GroupLayout.PREFERRED_SIZE, 31, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jButton2)
                            .addComponent(jTextField1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 12, Short.MAX_VALUE))))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void b_connectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_b_connectActionPerformed
        address = tf_address.getText();
        port = Integer.parseInt(tf_port.getText());
        
        tf_address.setEnabled(false);
        tf_port.setEnabled(false);
        b_connect.setEnabled(false);
        System.out.println("Starting socket");
        System.out.println(tf_address.getText()+ tf_port.getText());
        fd = this.netfifo_snd_open(tf_address.getText(),Integer.parseInt(tf_port.getText()),  64000);
        System.out.println("Sockets Created");
    }//GEN-LAST:event_b_connectActionPerformed

    private void b_disconnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_b_disconnectActionPerformed
        netfifo_snd_close(fd);
        b_connect.setEnabled(true);
        tf_address.setEnabled(true);
        tf_port.setEnabled(true);
        /*sendDisconnect();
        Disconnect();*/
    }//GEN-LAST:event_b_disconnectActionPerformed

    private void b_sendActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_b_sendActionPerformed
        netfifo_write(fd, tf_chat.getText().getBytes(), tf_chat.getText().getBytes().length);
        /*if(work_queue.offer(tf_chat.getText().getBytes())){
            System.out.println("DONE");
        }*/
        tf_chat.setText("");
        
    }//GEN-LAST:event_b_sendActionPerformed

    private void tf_chatActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_tf_chatActionPerformed
        b_send.doClick();
    }//GEN-LAST:event_tf_chatActionPerformed

    private void tf_addressKeyReleased(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_tf_addressKeyReleased
        //address=tf_address.getText();
    }//GEN-LAST:event_tf_addressKeyReleased

    private void tf_portKeyReleased(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_tf_portKeyReleased
        //port=Integer.parseInt(tf_port.getText());
    }//GEN-LAST:event_tf_portKeyReleased

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed
        ta_chat.setText("");
    }//GEN-LAST:event_jButton1ActionPerformed

    private void jTextField1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jTextField1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jTextField1ActionPerformed

    private void jButton2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton2ActionPerformed
        try {
            JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
            
            javax.swing.filechooser.FileFilter filter = new OpenFileFilter("rar", "TXT files");
            chooser.setFileHidingEnabled(true);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setFileFilter(filter);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.showOpenDialog(null);
            //chooser.setDragEnabled(true);
            
            File f = chooser.getSelectedFile();
            
            String filename = f.getPath();
            // inputPol = filename;
            jTextField1.setText(filename);
            
            if(work_queue.offer(Files.readAllBytes(Paths.get(filename)))){
                System.out.println("DONE");
            }
            tf_chat.setText("");
        } catch (IOException ex) {
            Logger.getLogger(client_frame.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }//GEN-LAST:event_jButton2ActionPerformed

    private void tf_addressActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_tf_addressActionPerformed
       b_connect.doClick();
    }//GEN-LAST:event_tf_addressActionPerformed

    public static void main(String args[]) 
    {

        java.awt.EventQueue.invokeLater(new Runnable() 
        {
            @Override
            public void run() 
            {
                new client_frame().setVisible(true);
            }
        });
    }

    public void checkHosts(String subnet) throws IOException {
        int timeout=300;
        for (int i=1;i<255;i++){
            String host=subnet + "." + i;
                if (InetAddress.getByName(host).isReachable(timeout)){
                    System.out.println(host + " is reachable");
                }

        }
    }
    
    

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton b_connect;
    private javax.swing.JButton b_disconnect;
    private javax.swing.JButton b_send;
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton2;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextField jTextField1;
    private javax.swing.JLabel lb_address;
    private javax.swing.JLabel lb_port;
    private javax.swing.JTextArea ta_chat;
    private javax.swing.JFormattedTextField tf_address;
    private javax.swing.JTextField tf_chat;
    private javax.swing.JFormattedTextField tf_port;
    // End of variables declaration//GEN-END:variables
}
