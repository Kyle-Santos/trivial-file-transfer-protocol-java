/*
    Download Operation (download)
        local file - directory where you are saving the file + the the file (ex. client-files/TestDownloadA.jpg)
        remote file - file to get from the server (ex. FileA.jpg)

    Upload Operation (upload)
        local file - directory where the client file is located + the file (ex. client-files/FileA.jpg)
        remote file - file name of the client file to be uploaded to the server (ex. TestUploadA.jpg)
 */


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
    private static final int TIMEOUT = 5000; // 5 seconds
    private static InetAddress serverAddress = null;
    private static String localFile = null;
    private static String remoteFile = null;
    private static int blocksize = 512;

    // TFTP OP Code
	private static final byte OP_RRQ = 1;
    private static final byte OP_WRQ = 2;
	private static final byte OP_DATAPACKET = 3;
	private static final byte OP_ACK = 4;
	private static final byte OP_ERROR = 5;

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: java Client <server_ip> <operation> <local_file> <remote_file>");
            return;
        }

        Scanner scan = new Scanner(System.in);

        String serverIP = args[0];
        String operation = args[1].toLowerCase();
        localFile = args[2];
        remoteFile = (args.length == 4) ? args[3] : null;

        String input;
        do {
            System.out.println("\nCurrent Setting:\nBlocksize: " + blocksize + "\nOperation: " + operation);
            System.out.println("\nMenu:\n[1] Proceed\n[2] Change Blocksize\n[3] Exit\n");
            input = scan.nextLine();

            switch (input) {
                case "1":
                    break;
                
                case "2":
                    System.out.print("\nEnter new blocksize: ");
                    blocksize = scan.nextInt();
                    scan.nextLine();
                    break;

                case "3":
                    return;
            
                default:
                    System.out.println("Invalid input.");
            }
        } while (!input.equals("1"));

        scan.close();

        try (DatagramSocket socket = new DatagramSocket()) {
            serverAddress = InetAddress.getByName(serverIP);
            socket.setSoTimeout(TIMEOUT);

            switch (operation) {
                case "upload":
                    uploadFile(socket);
                    break;
                case "download":
                    System.out.println("\nDownloading...");
                    downloadFile(socket);
                    break;
                default:
                    System.out.println("Invalid operation. Use 'upload' or 'download'.");
                    break;
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }


    private static void uploadFile(DatagramSocket socket) throws IOException {
        // Implementation for uploading file
        byte[] data = new byte[blocksize + 4];
        DatagramPacket packet;
        FileInputStream fileInputStream = new FileInputStream(localFile); 
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        
        // Create WRQ packet
        packet = createRequest(OP_WRQ);

        // sending write request to TFTP server 
        socket.send(packet);
        System.out.println("\nWRQ sent.");
        
        // Receiving ACK or error packet
        packet = new DatagramPacket(data, data.length);

        try {
            socket.receive(packet);
        } catch (IOException e) {
            System.out.println("\nError: Failed to contact server.");
            return;
        }

        // Check if received packet is an error packet
        if (data[1] == OP_ERROR) {
            handleTFTPError(packet);
            return;
        } 

        int blockNumber = 1;
        int bytesRead;

        while (true) {
            bytesRead = fileInputStream.read(data, 4, data.length - 4);

            if (bytesRead == -1) {
                // write opcode and block number in to the byte array output stream
                writeOPCodeBlock(byteArrayOutputStream, blockNumber, OP_DATAPACKET);
                packet = new DatagramPacket(byteArrayOutputStream.toByteArray(), byteArrayOutputStream.size(), serverAddress, packet.getPort());
                socket.send(packet);
                break;
            }

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

        System.out.println("You have successfully uploaded the file.");
        
        // Close file stream
        fileInputStream.close();
    }

    private static void downloadFile(DatagramSocket socket) throws IOException {
        // Implementation for downloading file
        byte[] data = new byte[blocksize + 4];
        DatagramPacket inpacket, outpacket ;
        DatagramPacket ack = null;
        FileOutputStream fileOutputStream = new FileOutputStream(localFile);
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream();;
        
        outpacket = createRequest(OP_RRQ);

        // sending read request to TFTP server 
        socket.send(outpacket);
        System.out.println("\nRRQ sent.");

        // Receiving ACK or error packet
        inpacket = new DatagramPacket(data, data.length);

        try {
            socket.receive(inpacket);
        } catch (IOException e) {
            System.out.println("\nError: Failed to contact server.");
            return;
        }

        // Check if received packet is an error packet
        if (data[1] == OP_ERROR) {
            handleTFTPError(inpacket);
            return;
        } else {
            // Create and send ack packet
            writeOPCodeBlock(packetStream, 0, OP_ACK);
            ack = new DatagramPacket(packetStream.toByteArray(), packetStream.size(), serverAddress, inpacket.getPort());
            socket.send(ack);
        }
        
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
                handleTFTPError(inpacket);
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
                    break; // Exit loop if last data block received
                }
            } 
        }
        
        System.out.println("\nDownloading of the file is successful.");
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
        byteArrayOutputStream.write(Integer.toString(blocksize).getBytes());
        byteArrayOutputStream.write(0);

        // tsize request
        if (OP == OP_WRQ && localFile != null) {
            File file = new File(localFile);
            long fileSize = file.length();
            if (fileSize > 0) {
                // Append tsize option
                byteArrayOutputStream.write("tsize".getBytes());
                byteArrayOutputStream.write(0);
                byteArrayOutputStream.write(Long.toString(fileSize).getBytes());
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
