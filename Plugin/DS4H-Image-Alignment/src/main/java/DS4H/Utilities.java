package DS4H;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Class containing various utilities of the plugin
 */
public class Utilities {
    public static void setTimeout(Runnable runnable, int delay){
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                runnable.run();
            }
            catch (Exception e){
                System.out.println(e);
            }
        }).start();
    }

    // thanks to https://stackoverflow.com/a/1264737/1306679
    public static byte[] inputStreamToByteArray(InputStream inputStream, int bufferSize) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[bufferSize];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1)
            buffer.write(data, 0, nRead);

        return buffer.toByteArray();
    }

    public static byte[] inputStreamToByteArray(InputStream inputStream) throws IOException {
        return inputStreamToByteArray(inputStream, 16384);
    }
}
