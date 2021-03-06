package mini;

import com.sun.xml.internal.fastinfoset.util.StringArray;
import javafx.util.Pair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.net.ftp.FTPClient;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.io.*;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.codec.binary.Base32;

import static mini.ClientMain.GUI_ENABLED;
import static mini.ClientMain.client;

public class ClientHandler {
    private static final String FILE_DELETION_SUCCESS = "File Successfully Deleted : ";
    private static final String FILE_DELETION_FAILURE = "File Could not be Deleted : ";
    private static final String FILE_OVERWRITE_PROMPT = "File already exists. overwrite? y/n";
    private static final String FILE_RENAME_ILLEGAL = "Illegal Number of Arguments ";
    private static final String MFILE_NAME = "nothing_important_here";
    private static  Base32 base32 = new Base32();

    /**
     * Split an input with regex " \" " in case file names are involved
     * or split spaces if not
     * @param command
     * @return
     */
    public static String[] parseCommand(String command){
        String[] splitCommand = command.split(" ");
        if(handlesAFile(splitCommand[0])){
            String[] paths = command.split("\"");
            return AuxFunctions.removeSpaces(paths);    //remove spaces - side effects of splitting with "
        }
        else
            return command.split(" ");
    }

    /**
     * Checks if an opcode represents a Command that handles file names (and should be aware of spaces
     * @param opcode
     * @return
     */
    private static boolean handlesAFile(String opcode){
        return (opcode.equals("write")|| opcode.equals("meta") || opcode.equals("delete")|| opcode.equals("rename"));
    }
    /**
     * Handles the connection to the server
     */
    public static void handleConnection() {

        String input;
        String[] command;

        while (client.isConnected()) {
            Scanner sc = new Scanner(System.in);
            input = sc.nextLine();
            command = parseCommand(input);
            if (command.length > 0) {
                switch (command[0]) {
                    case "ls":
                        handleListCommand();
                        break;
                    case "meta":
                        handleMeta(command);
                        break;
                    case "write":
                        handleWrite(command);
                        break;
                    case "read":
                        handleRead(command);
                        break;
                    case "delete":
                        handleDelete(command);
                        break;
                    case "exit":
                        try {
                            System.out.println("Exiting ...");
                            client.logout();
                            client.disconnect();

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    case "rename":
                        handleRename(command);
                        break;
                    default:
                        System.out.println("Unknown command");
                }
            } else {
                //TODO: COMMAND INPUT ERROR
            }


        }
    }

    /**
     * Handles the "rename" command
     *
     * @param command
     */
    protected static boolean handleRename(String[] command) {
        boolean nameChanged = false;
        if (command.length == 3) {

            try {
                byte[] originEncTag = encryptAndTagName(command[1]);     //original file name - encrypted and tagged
                byte[] changeToEncTag = encryptAndTagName(command[2]);   //new file name -  same
                nameChanged = client.rename(base32.encodeAsString(originEncTag), base32.encodeAsString(changeToEncTag));
                if(nameChanged)
                    writeMFileOnServer();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else
            System.out.println(FILE_RENAME_ILLEGAL);
        return nameChanged;
    }

    /**
     * Handle a DELETE command
     *
     * @param command
     * @return true if the file was deleted from server properly
     */
    protected static boolean handleDelete(String[] command) {

        for (String name : command) {
            if (name.equals(command[0])) //skip command name
                continue;
            try {
                if (client.deleteFile(base32.encodeAsString(encryptAndTagName(name)))) {
                    System.out.print(FILE_DELETION_SUCCESS);
                    System.out.println(name);
                    writeMFileOnServer();
                    return true;
                } else {
                    System.out.print(FILE_DELETION_FAILURE);
                    System.out.println(name);
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * Handles a READ command
     * @param command
     */
    private static void handleRead(String[] command) {
        for (int i = 1; i < command.length; i++) {
            String name = command[i];
            File file = new File(name);

            try {
                byte[] originFile;
                if ((originFile = readFileToRAM(name)) == null)
                    return ; //TODO: Some kind of error
                FileUtils.writeByteArrayToFile(file, originFile); // writing byte array to file
                System.out.println(client.getReplyString());
                return;

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return;
    }


    /**
     * Encrypt a file and add a tag (suffix) for authentication
     *
     * @param fis
     * @return a InputStream ready to be sent to server
     * @throws IOException
     */
    private static InputStream encryptAndTagFile(InputStream fis) throws IOException {
        byte[] originBytesToWrite = IOUtils.toByteArray(fis);
        byte[] encryptedBytesToWrite = encryptData(originBytesToWrite, ClientMain.key1ForEncryption);
        byte[] tag = getAuthenticationTag(encryptedBytesToWrite, ClientMain.key2ForAuthen);
        //merging 2 bytes array encryptedBytesToWrite + tag
        byte[] bytesToWrite = new byte[encryptedBytesToWrite.length + tag.length];
        System.arraycopy(encryptedBytesToWrite, 0, bytesToWrite, 0, encryptedBytesToWrite.length);
        System.arraycopy(tag, 0, bytesToWrite, encryptedBytesToWrite.length, tag.length);
        fis.close();
        return new ByteArrayInputStream(bytesToWrite);
    }

    /**
     * Encrypt a file name
     *
     * @param originFileName
     * @return
     * @throws IOException
     */
    private static byte[] encryptAndTagName(String originFileName) throws IOException {
        byte[] originBytes = originFileName.getBytes();
        byte[] encryptedNameBytes = encryptData(originBytes, ClientMain.key1ForEncryption);
        byte[] tag = getAuthenticationTag(encryptedNameBytes, ClientMain.key2ForAuthen);
        byte[] ready = new byte[encryptedNameBytes.length + tag.length];
        System.arraycopy(encryptedNameBytes, 0, ready, 0, encryptedNameBytes.length);
        System.arraycopy(tag, 0, ready, encryptedNameBytes.length, tag.length);
        return ready;
    }

    /**
     * Decrypt and Authenticate a file name
     *
     * @param encName
     * @return
     */
    private static String decryptAndAuthName(String encName) {
        byte[] encAuthNameBytes =base32.decode(encName);
        // byte[] encAuthNameBytes=encName.getBytes();
        byte[] encNameBytes = authenticateData(encAuthNameBytes, ClientMain.key2ForAuthen);
        if (encNameBytes == null) {
            System.out.println("File name Authentication failed");
            return null;
        }
        byte[] nameBytes = decryptData(encNameBytes, ClientMain.key1ForEncryption);
        if (nameBytes != null) {
            return new String(nameBytes);
        } else return null;
    }

    /**
     * Handles a write command
     *
     * @param command
     */
    protected static void handleWrite(String[] command) {
        for (String filePath : command) {
            if (filePath.equals(" ") || ArrayUtils.indexOf(command, filePath) == 0)
                continue;
            String path = filePath;

            File file = new File(path);
            try {
                InputStream fis = new FileInputStream(file);
                byte[] encAuthFileName = encryptAndTagName(getNameFromPath(path));
                InputStream readyForWriting = encryptAndTagFile(fis);
                //if file exists
                if (ArrayUtils.contains(client.listNames(), base32.encodeAsString(encAuthFileName))) {
                    //ask user to overwrite
                    if (promptOverWrite(getNameFromPath(filePath))) {
                        if(client.storeFile(base32.encodeAsString(encAuthFileName), readyForWriting))
                            writeMFileOnServer();
                        System.out.println(client.getReplyString());
                    }
                } else {
                    client.storeFile(base32.encodeAsString(encAuthFileName), readyForWriting);
                    writeMFileOnServer();
                    System.out.println(client.getReplyString());
                }
                readyForWriting.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

    }

    /**
     * Prompts the user for overwriting a file
     *
     * @param name
     * @return
     */
    private static boolean promptOverWrite(String name) {
        if(ClientMain.GUI_ENABLED){
            if(JOptionPane.showConfirmDialog(null,"Overwrite file "+name+"?") == JOptionPane.YES_OPTION)
                return true;
        }
        System.out.print(name + " : ");
        System.out.println(FILE_OVERWRITE_PROMPT);
        Scanner sc = new Scanner(System.in);
        char input = sc.next(".").charAt(0);
        return (input == 'y');

    }

    /**
     * Get file name from the Path
     *
     * @param path
     * @return
     */
    private static String getNameFromPath(String path) {
        if (path.contains("\\")) {
            String[] path_names = path.split("\\\\");
            return path_names[path_names.length - 1];
        } else
            return path;
    }

    /**
     * Handles an "ls" command
     */
    protected static ArrayList<String> handleListCommand() {
        try {
            String[] names = client.listNames();
            ArrayList<String> decNames=new ArrayList<>();
            if (names != null && names.length > 0) {
                for (String fileName : names) {
                    if(fileName.equals(MFILE_NAME))
                        continue;
                    String decName = decryptAndAuthName(fileName);
                    if(decName!=null && decName.equals(MFILE_NAME))
                        continue;
                    decNames.add(decName);
                    if (decName == null)
                        return null;
                    if(!GUI_ENABLED)
                        System.out.println(decName);
                }
            }
            return decNames;
        } catch (IOException e) {
            e.printStackTrace();
            return  null;
        }

    }

    /**
     * encrypt data with encryptionKey using cipher with AES algorithm.
     * returning the encrypted data as string or null if fails.
     *
     * @param data
     * @param encryptionKey
     * @return
     */
    private static byte[] encryptData(byte[] data, byte[] encryptionKey) {
        try {
            Key aesKey = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Decrypt encrypted data with encryptionKey using AES and Cipher.
     * returning original data.
     *
     * @param encryptedData
     * @param encryptionKey
     * @return
     */
    private static byte[] decryptData(byte[] encryptedData, byte[] encryptionKey) {
        try {
            Key aesKey = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            return cipher.doFinal(encryptedData);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * creating tag of length 256bit using Mac with authenKey on data.
     * returning the tag as string or null if fails.
     *
     * @param data
     * @param authenKey
     * @return
     */
    private static byte[] getAuthenticationTag(byte[] data, byte[] authenKey) {
        try {
            SecretKeySpec macKey = new SecretKeySpec(authenKey, "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return mac.doFinal(data);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * split the data to message and tag and checks if : tag = MAC(message,key).
     * if yes return message, else return null.
     *
     * @param data
     * @param authenKey
     * @return
     */
    private static byte[] authenticateData(byte[] data, byte[] authenKey) {
        try {
            SecretKeySpec macKey = new SecretKeySpec(authenKey, "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            if(data.length < 32)
                return null;
            int messageLength = data.length - 32; // 32 is the tag length in bytes = 256bit.
            byte[] message = new byte[messageLength];
            byte[] tag = new byte[32];
            System.arraycopy(data, 0, message, 0, messageLength);
            System.arraycopy(data, messageLength, tag, 0, tag.length);
            if (Arrays.equals(mac.doFinal(message), tag))
                return message;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Handles the meta command
     *
     * @param command
     * @return
     */
    protected static FileMetaData handleMeta(String[] command) {

        String filename = command[1];
        try {
            FTPFile[] allFiles = client.listFiles();
            String encFilename = base32.encodeAsString(encryptAndTagName(filename));  //the encrypted file name
            for (FTPFile file : allFiles) {
                if (file.getName().equals(encFilename)) {
                    //create the File MetaData Object  to Return;
                    FileMetaData metaToReturn=new FileMetaData(filename,file.getTimestamp(),file.getSize());

                    String size = Long.toString(file.getSize()) + "Bytes";
                    Calendar dateModified = file.getTimestamp();
                    SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                    format.setTimeZone(TimeZone.getDefault());
                    String metaData="Name: " + filename + "\n" + "Size: " + size + "\n" + "Last Modified: " + format.format(dateModified.getTime());
                    if(!GUI_ENABLED)
                        System.out.println(metaData);
                    return metaToReturn;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * Compare Two byte arrays, using the Authentication key, and HASH
     * @param arr1
     * @param arr2
     * @return
     */
    static private boolean hashCompareByteArrays(byte[] arr1,byte[] arr2){
        byte[] arr1_tag=getAuthenticationTag(arr1,ClientMain.key2ForAuthen);
        byte[] arr2_tag=getAuthenticationTag(arr2,ClientMain.key2ForAuthen);
        return Arrays.equals(arr1_tag,arr2_tag);
    }
    /**
     * Write the Management File on server - the file is built according
     * to the metadata of all the files on the server
     */

    /**
     * Get the servers current MetaData and write a new Management file on it
     */
    protected static void writeMFileOnServer() {
        try {
            handleDelete(parseCommand("delete "+MFILE_NAME));
            byte[] currentMetaData = getCurrentMetaData();
            byte[] encAuthFileName = encryptAndTagName(MFILE_NAME);
            InputStream in = new ByteArrayInputStream(currentMetaData);
            InputStream readyForWriting = encryptAndTagFile(in);
            if(!ClientMain.client.storeFile(base32.encodeAsString(encAuthFileName), readyForWriting)){
                System.out.println("Error storing new MFile!");
            }
            readyForWriting.close();
            System.out.println(ClientMain.client.getReplyString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Make sure the Management file on server is up-to-date
     * according to the Server's contents
     * @return
     */
    protected static boolean authenticateMFileData() {

        try {
            byte[] currentMetaData = getCurrentMetaData();
            //READ the file byte data to Client RAM (authenticated and decrypted)
            byte[] managementData = readFileToRAM(MFILE_NAME);
            if (managementData == null) {
                System.out.println("authenticateMFileData   -   readFileToRam is NULL!!");
                return false;
            }
            return hashCompareByteArrays(currentMetaData,managementData);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
}

    /**
     * Get metadata of ALL files on server and return a byte array of this data.
     * TODO: check order of returned FTPFiles.
     * @return
     */
    private static byte[] getCurrentMetaData() {
        try {
            //list all files
            FTPFile[] files = client.listFiles();
            if(files != null){
                StringBuilder stringBuilder=new StringBuilder();
                for(FTPFile f: files){
                    if(f.getName().equals(MFILE_NAME))
                        continue;
                    if(f.getName().equals(base32.encodeAsString(encryptAndTagName(MFILE_NAME))))   //ignore the management file
                        continue;

                    //append file name
                    stringBuilder.append(f.getName()+",");
                    //append file size
                    stringBuilder.append(Long.toString(f.getSize())+",");
                    //append file modification date!
                    stringBuilder.append(Long.toString(f.getTimestamp().getTimeInMillis()));
                    //newline
                    stringBuilder.append('\n');
                }
                return stringBuilder.toString().getBytes();
            }
            else{
                System.out.println("ClientMain.client.listFiles()   =   RETURNED NULL");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Fetch a file from server -
     * but first authenticate it with the Key
     * @param fileName
     * @return  byte array of the file, or null when file was tampered with
     * @throws IOException
     */
    protected static byte[] readFileToRAM(String fileName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if(!client.retrieveFile(base32.encodeAsString(encryptAndTagName(fileName)), out)){
            System.out.println("readFileToRam with  -"+fileName+" has failed");
        }
        byte[] bytesRead = out.toByteArray();
        out.close();
        byte[] encryptedFile = authenticateData(bytesRead, ClientMain.key2ForAuthen);
        if (encryptedFile == null) {
            System.out.println("file damaged");
            return null;
        }
        return decryptData(encryptedFile, ClientMain.key1ForEncryption);

    }

}
