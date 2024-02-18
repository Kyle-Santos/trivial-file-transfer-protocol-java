import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;

public class Client {
    private static final int SERVER_PORT = 69;
    private static final int TIMEOUT = 10000; // 10 seconds
    private static InetAddress serverAddress = null;
    private static String remoteFile = null;
    private static int blocksize = 516;

    // TFTP OP Code
	private static final byte OP_RRQ = 1;
    private static final byte OP_WRQ = 2;
	private static final byte OP_DATAPACKET = 3;
	private static final byte OP_ACK = 4;
	private static final byte OP_ERROR = 5;

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: java TFTPClient <server_ip> <operation> <local_file> [<remote_file>]");
            return;
        }

        Scanner scan = new Scanner(System.in);

        String serverIP = args[0];
        String operation = args[1].toLowerCase();
        String localFile = args[2];
        remoteFile = (args.length == 4) ? args[3] : null;

        String input;
        do {
            System.out.println("Current Setting:\nBlocksize: " + blocksize + "\nOperation: " + operation);
            System.out.println("\nMenu:\n[1] Proceed\n[2] Change Blocksize\n[3] Exit\n");
            input = scan.nextLine();

            switch (input) {
                case "1":
                    System.out.println("Enter new blocksize: ");
                    blocksize = scan.nextInt();
                    break;
                
                case "2":
                    break;

                case "3":
                    return;
            
                default:
                    System.out.println("Invalid input.");
            }
        } while (input.equals("2"));

        try (DatagramSocket socket = new DatagramSocket()) {
            serverAddress = InetAddress.getByName(serverIP);
            socket.setSoTimeout(TIMEOUT);

            switch (operation) {
                case "upload":
                    uploadFile(socket, localFile);
                    break;
                case "download":
                    System.out.println("Downloading...");
                    downloadFile(socket, localFile);
                    break;
                default:
                    System.out.println("Invalid operation. Use 'upload' or 'download'.");
                    break;
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }


    private static void uploadFile(DatagramSocket socket, String localFile) throws IOException {
        // Implementation for uploading file
        byte[] data = new byte[516];
        DatagramPacket packet;
        FileInputStream fileInputStream = new FileInputStream(localFile);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        
        // Create WRQ packet
        packet = createRequest(OP_WRQ);

        // sending write request to TFTP server 
        socket.send(packet);
        System.out.println("WRQ sent.");
        
        // Receiving ACK or error packet
        packet = new DatagramPacket(data, data.length);
        socket.receive(packet);

        // Check if received packet is an error packet
        if (data[1] == OP_ERROR) {
            handleTFTPError(packet);
            return;
        }

        int blockNumber = 1;

        int bytesRead;

        while ((bytesRead = fileInputStream.read(data, 4, data.length - 4)) != -1) {
            System.out.println("Packet count: " + blockNumber);

            // write opcode and block number in to the byte array output stream
            writeOPCodeBlock(byteArrayOutputStream, blockNumber, OP_DATAPACKET);

            // write data bytes
            byteArrayOutputStream.write(data, 4, bytesRead);
            
            // send data packet
            packet = new DatagramPacket(byteArrayOutputStream.toByteArray(), byteArrayOutputStream.size(), serverAddress, packet.getPort());
            socket.send(packet);
            
            // Receiving ACK
            packet = new DatagramPacket(data, data.length);
            socket.receive(packet);
            blockNumber++;

            // Check if received packet is an error packet
            if (data[1] == OP_ERROR) {
                handleTFTPError(packet);
                return;
            }
        }
        
        // Close file stream
        fileInputStream.close();
    }

    private static void downloadFile(DatagramSocket socket, String localFile) throws IOException {
        // Implementation for downloading file
        byte[] data = new byte[516];
        DatagramPacket inpacket, outpacket ;
        DatagramPacket ack = null;
        FileOutputStream fileOutputStream = new FileOutputStream(localFile);
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream();;
        
        outpacket = createRequest(OP_RRQ);

        // sending read request to TFTP server 
        socket.send(outpacket);
        System.out.println("RRQ sent.");
        
        // Track block number of ACKs
        int blockNumber = 1;

        // Receive data packets
        while (true) {
            System.out.println("Packet count: " + blockNumber);
            inpacket = new DatagramPacket(data, data.length, serverAddress, socket.getLocalPort());

            // receive packet from TFTP server
            socket.receive(inpacket);

            // Check if received packet is an error packet
            if (data[1] == OP_ERROR) {
                handleTFTPError(packet);
                return;
            }

            // Extract block number from the received packet
            int receivedBlockNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

            // Check if received block number matches expected block number
            if (receivedBlockNumber == blockNumber) {
                // Write data to file
                fileOutputStream.write(data, 4, inpacket.getLength() - 4);
                
                // Create and send ack packet
                writeOPCodeBlock(packetStream, blockNumber, OP_ACK);
                ack = new DatagramPacket(packetStream.toByteArray(), packetStream.size(), serverAddress, inpacket.getPort());
                socket.send(ack);

                // Increment block number for next expected block
                blockNumber++;

                // Check if last data block received
                if (inpacket.getLength() < 516) {
                    System.out.println(inpacket.getLength() + "..");
                    System.out.println("last packet!");
                    break; // Exit loop if last data block received
                }
            } 
        }
        
        System.out.println("Downloading done.");
        // Close file stream
        fileOutputStream.close();
    }

    private static DatagramPacket createRequest(byte OP) throws IOException {
        // creating the initial RRQ packet
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(0);
        byteArrayOutputStream.write(OP);
        byteArrayOutputStream.write(remoteFile.getBytes());
        byteArrayOutputStream.write(0);
        byteArrayOutputStream.write("octet".getBytes());
        byteArrayOutputStream.write(0);

        // blksize request | Append blksize option (example: blksize 1024)
        byteArrayOutputStream.write("blksize".getBytes());
        byteArrayOutputStream.write(0);
        // Write the high byte of block size
        byteArrayOutputStream.write((blocksize >> 8) & 0xFF);
        // Write the low byte of block size
        byteArrayOutputStream.write(blocksize & 0xFF);
        byteArrayOutputStream.write(0);

        // tsize request
        if (OP == OP_WRQ && remoteFile != null) {
            File file = new File(remoteFile);
            long fileSize = file.length();
            if (fileSize > 0) {
                // Append tsize option
                byteArrayOutputStream.write("tsize".getBytes());
                byteArrayOutputStream.write(0);
                // Write the file size as a 32-bit binary value in network byte order
                byteArrayOutputStream.write((int) (fileSize >> 24) & 0xFF);
                byteArrayOutputStream.write((int) (fileSize >> 16) & 0xFF);
                byteArrayOutputStream.write((int) (fileSize >> 8) & 0xFF);
                byteArrayOutputStream.write((int) fileSize & 0xFF);
                byteArrayOutputStream.write(0);
            }
        }

        return new DatagramPacket(byteArrayOutputStream.toByteArray(), byteArrayOutputStream.size(), serverAddress, SERVER_PORT);
    }

    private static ByteArrayOutputStream writeOPCodeBlock(ByteArrayOutputStream packetStream, int blockNumber, byte OP) throws IOException {
        packetStream.reset();
        packetStream.write(0);
        packetStream.write(OP);
        packetStream.write((byte) (blockNumber >> 8));
        packetStream.write((byte) blockNumber);

        return packetStream;
    }

    private static void handleTFTPError(DatagramPacket packet) {
        byte[] data = packet.getData();
        int errorCode = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        String errorMessage = new String(data, 4, packet.getLength() - 4);
        System.out.println("Error received from server: " + errorCode + " - " + errorMessage);
    }
    
}
