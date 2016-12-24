/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.systemTray.util;

import static dorkbox.systemTray.jna.Windows.Gdi32.GetDeviceCaps;
import static dorkbox.systemTray.jna.Windows.Gdi32.LOGPIXELSX;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

import com.sun.jna.Pointer;

import dorkbox.systemTray.SystemTray;
import dorkbox.systemTray.jna.Windows.User32;
import dorkbox.util.CacheUtil;
import dorkbox.util.FileUtil;
import dorkbox.util.IO;
import dorkbox.util.LocationResolver;
import dorkbox.util.OS;
import dorkbox.util.SwingUtil;
import dorkbox.util.process.ShellProcessBuilder;

public
class ImageUtils {

    private static final File TEMP_DIR = new File(CacheUtil.TEMP_DIR, "ResizedImages");

    // tray/menu-entry size.
    // for more complete info on the linux side of things...
    // https://wiki.archlinux.org/index.php/HiDPI
    public static volatile int TRAY_SIZE = 0;
    public static volatile int ENTRY_SIZE = 0;

    public static
    void determineIconSize() {
        double trayScalingFactor = 0;
        double menuScalingFactor = 0;

        if (SystemTray.AUTO_TRAY_SIZE) {
            if (OS.isWindows()) {
                // if windows 8.1/10 - default size is x2
                String windowsVersion = "";
                try {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(8196);
                    PrintStream outputStream = new PrintStream(byteArrayOutputStream);

                    // cmd.exe /c ver
                    final ShellProcessBuilder shellVersion = new ShellProcessBuilder(outputStream);
                    shellVersion.setExecutable("cmd.exe");
                    shellVersion.addArgument("/c");
                    shellVersion.addArgument("ver");
                    shellVersion.start();

                    String output = ShellProcessBuilder.getOutput(byteArrayOutputStream);

                    if (!output.isEmpty()) {
                        if (SystemTray.DEBUG) {
                            SystemTray.logger.debug("Checking windows version, value: '{}'", output);
                        }

                        // should be: Microsoft Windows [Version 10.0.10586]   or something
                        if (output.contains("ersion ")) {
                            int beginIndex = output.indexOf("ersion ") + 7;
                            windowsVersion = output.substring(beginIndex, beginIndex+6);
                        }
                    }
                } catch (Throwable e) {
                    if (SystemTray.DEBUG) {
                        SystemTray.logger.error("Cannot check windows version factor", e);
                    }
                }

                // vista - 8.0 - only global DPI settings
                // 8.1 - 10 - global + per-monitor DPI settings

                // 1 = 16
                // 2 = 32
                // 4 = 64
                // 8 = 128

                if (windowsVersion.startsWith("5.1")) {
                    // Windows XP	5.1.2600
                    trayScalingFactor = 1;

                } else if (windowsVersion.startsWith("5.1")) {
                    // Windows Server 2003	5.2.3790.1218
                    trayScalingFactor = 1;

                } else if (windowsVersion.startsWith("6.0")) {
                    // Windows Vista	        6.0.6000
                    // Windows Server 2008 SP1	6.0.6001
                    // Windows Server 2008 SP2	6.0.6002
                    trayScalingFactor = 1;

                } else if (windowsVersion.startsWith("6.1")) {
                    // Windows 7
                    // Windows Server 2008 R2       6.1.7600
                    // Windows Server 2008 R2 SP1   6.1.7601
                    trayScalingFactor = 2;

                } else if (windowsVersion.startsWith("6.2")) {
                    // Windows 8
                    // Windows Server 2012	6.2.9200
                    trayScalingFactor = 2;

                } else if (windowsVersion.startsWith("6.3")) {
                    // Windows 8.1
                    // Windows Server 2012	6.3.9200
                    trayScalingFactor = 4;

                } else if (windowsVersion.startsWith("6.4")) {
                    // Windows 10 Technical Preview 1	6.4.9841
                    trayScalingFactor = 4;

                } else if (windowsVersion.startsWith("10.0")) {
                    // Windows 10 Technical Preview 4	10.0.9926
                    // Windows 10 Insider Preview	    10.0.14915
                    trayScalingFactor = 4;

                } else {
                    // dunnno, but i'm going to assume HiDPI for this...
                    trayScalingFactor = 8;
                }

                Pointer screen = User32.GetDC(null);
                int dpiX = GetDeviceCaps (screen, LOGPIXELSX);
                User32.ReleaseDC(null, screen);

                // 96 DPI = 100% scaling
                // 120 DPI = 125% scaling
                // 144 DPI = 150% scaling
                // 192 DPI = 200% scaling

                // just a note on scaling...
                // We want to scale the image as best we can beforehand, so there is an attempt to have it look good
                if (dpiX != 96) {
                    // so there are additional scaling settings...
                    // casting around for rounding/math stuff
                    // *2 because we want a 2x scale to be 64, not 32.
                    trayScalingFactor = (int) (((double) dpiX) / ((double) 96)) * 2;
                    menuScalingFactor = trayScalingFactor;
                }


                if (SystemTray.DEBUG) {
                    SystemTray.logger.debug("Windows version (partial): '{}'", windowsVersion);
                    SystemTray.logger.debug("Windows DPI settings: '{}'", dpiX);
                }
            } else if (OS.isLinux()) {
                // GtkStatusIcon will USUALLY automatically scale the icon
                // AppIndicator MIGHT scale the icon (depends on the OS)


                // KDE is bonkers. Gnome is "ok"
                String XDG = System.getenv("XDG_CURRENT_DESKTOP");
                if (XDG == null) {
                    // Check if plasmashell is running, if it is -- then we are most likely KDE

                    try {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(8196);
                        PrintStream outputStream = new PrintStream(byteArrayOutputStream);

                        // ps a | grep [g]nome-shell
                        final ShellProcessBuilder shell = new ShellProcessBuilder(outputStream);
                        shell.setExecutable("ps");
                        shell.addArgument("a");
                        shell.start();


                        String output = ShellProcessBuilder.getOutput(byteArrayOutputStream);
                        if (output.contains("plasmashell")) {
                            XDG = "kde";
                        }
                    } catch (Throwable e) {
                        // assume we are not KDE. Maybe not the best assumption, but if we get here, something is wrong.
                        if (SystemTray.DEBUG) {
                            SystemTray.logger.error("Unable to check if plasmashell is running.", e);
                        }
                    }
                }


                if ("kde".equalsIgnoreCase(XDG)) {
                    try {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(8196);
                        PrintStream outputStream = new PrintStream(byteArrayOutputStream);

                        // plasma-desktop -v
                        // plasmashell --version
                        final ShellProcessBuilder shellVersion = new ShellProcessBuilder(outputStream);
                        shellVersion.setExecutable("plasmashell");
                        shellVersion.addArgument("--version");
                        shellVersion.start();

                        String output = ShellProcessBuilder.getOutput(byteArrayOutputStream);

                        if (!output.isEmpty()) {
                            if (SystemTray.DEBUG) {
                                SystemTray.logger.debug("Checking plasma KDE environment, should start with 'plasmashell', value: '{}'", output);
                            }

                            // DEFAULT icon size is 16. KDE is bananas on what they did with tray icon scale
                            // should be: plasmashell 5.6.5   or something
                            String s = "plasmashell ";
                            if (output.contains(s)) {
                                String value = output.substring(output.indexOf(s) + s.length(), output.length());

                                // 1 = 16
                                // 2 = 32
                                // 4 = 64
                                // 8 = 128
                                if (value.startsWith("4")) {
                                    trayScalingFactor = 2;
                                } else if (value.startsWith("5")) {
                                    trayScalingFactor = 8; // it is insane how large the icon is
                                } else {
                                    // assume very low version of plasmashell, default 32
                                    trayScalingFactor = 2;
                                }
                            }
                        }
                    } catch (Throwable e) {
                        if (SystemTray.DEBUG) {
                            SystemTray.logger.error("Cannot check plasmashell version", e);
                        }
                    }
                } else {
                    // it's likely a Gnome environment

                    try {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(8196);
                        PrintStream outputStream = new PrintStream(byteArrayOutputStream);

                        // gsettings get org.gnome.desktop.interface scaling-factor
                        final ShellProcessBuilder shellVersion = new ShellProcessBuilder(outputStream);
                        shellVersion.setExecutable("gsettings");
                        shellVersion.addArgument("get");
                        shellVersion.addArgument("org.gnome.desktop.interface");
                        shellVersion.addArgument("scaling-factor");
                        shellVersion.start();

                        String output = ShellProcessBuilder.getOutput(byteArrayOutputStream);

                        if (!output.isEmpty()) {
                            if (SystemTray.DEBUG) {
                                SystemTray.logger.debug("Checking scaling factor for GTK environment, should start with 'uint32', value: '{}'", output);
                            }

                            // DEFAULT icon size is 16. HiDpi changes this scale, so we should use it as well.
                            // should be: uint32 0  or something
                            if (output.contains("uint32")) {
                                String value = output.substring(output.indexOf("uint")+7, output.length());
                                trayScalingFactor = Integer.parseInt(value);

                                // 0 is disabled (no scaling)
                                // 1 is enabled (default scale)
                                // 2 is 2x scale
                                // 3 is 3x scale
                                // etc


                                // A setting of 2, 3, etc, which is all you can do with scaling-factor
                                // To enable HiDPI, use gsettings:
                                // gsettings set org.gnome.desktop.interface scaling-factor 2
                            }
                        }
                    } catch (Throwable e) {
                        if (SystemTray.DEBUG) {
                            SystemTray.logger.error("Cannot check scaling factor", e);
                        }
                    }

                    // fedora 24 has a wonky size for the indicator (NOT default 16px)
                    int fedoraVersion = Integer.parseInt(System.getProperty("SystemTray_IS_FEDORA_ADJUST_SIZE", "0"));
                    if (trayScalingFactor == 0 && fedoraVersion >= 23) {
                        if (SystemTray.DEBUG) {
                            SystemTray.logger.debug("Adjusting tray/menu scaling for FEDORA " + fedoraVersion);
                        }
                        trayScalingFactor = 2;
                        menuScalingFactor = 1.5;
                    }
                }
            } else if (OS.isMacOsX()) {
                // let's hope this wasn't just hardcoded like windows...
                int height;

                if (!SwingUtilities.isEventDispatchThread()) {
                    // MacOS must execute this on the EDT... It is very strict compared to win/linux
                    final AtomicInteger h = new AtomicInteger(0);
                    SwingUtil.invokeAndWaitQuietly(new Runnable() {
                        @Override
                        public
                        void run() {
                            h.set((int) java.awt.SystemTray.getSystemTray()
                                                           .getTrayIconSize()
                                                           .getHeight());
                        }
                    });
                    height = h.get();
                } else {
                    height = (int) java.awt.SystemTray.getSystemTray()
                                                      .getTrayIconSize()
                                                      .getHeight();
                }

                if (height < 32) {
                    // lock in at 32
                    trayScalingFactor = 2;
                }
                else if ((height & (height - 1)) == 0) {
                    // is this a power of 2 number? If so, we can use it
                    trayScalingFactor = height/SystemTray.DEFAULT_TRAY_SIZE;
                }
                else {
                    // don't know how exactly to determine this, but we are going to assume very high "HiDPI" for this...
                    // the OS should go up/down as needed.
                    trayScalingFactor = 8;
                }
            }
        }

        // windows, mac, linux(GtkStatusIcon) will automatically scale the tray size
        //  the menu entry icon size will NOT get scaled (it will show whatever we specify)
        // we want to make sure our "scaled" size is appropriate for the OS.

        // the DEFAULT scale is 16
        if (trayScalingFactor > 1) {
            TRAY_SIZE = (int) (SystemTray.DEFAULT_TRAY_SIZE * trayScalingFactor);
        } else {
            TRAY_SIZE = SystemTray.DEFAULT_TRAY_SIZE;
        }

        if (menuScalingFactor > 1) {
            ENTRY_SIZE = (int) (SystemTray.DEFAULT_MENU_SIZE * menuScalingFactor);
        } else {
            ENTRY_SIZE = SystemTray.DEFAULT_MENU_SIZE;
        }

        if (SystemTray.DEBUG) {
            SystemTray.logger.debug("Scaling Factor factor is '{}', tray icon size is '{}'.", trayScalingFactor, TRAY_SIZE);
            SystemTray.logger.debug("Scaling Factor factor is '{}', tray menu size is '{}'.", menuScalingFactor, ENTRY_SIZE);
        }
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static
    File getTransparentImage(final int size) {
        final File newFile = new File(TEMP_DIR, size + "_empty.png").getAbsoluteFile();

        if (newFile.canRead() && newFile.isFile()) {
            return newFile;
        }

        // make sure the directory exists
        newFile.getParentFile().mkdirs();

        try {
            final BufferedImage image = getTransparentImageAsImage(size);
            ImageIO.write(image, "png", newFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return newFile;
    }

    @SuppressWarnings("WeakerAccess")
    public static
    BufferedImage getTransparentImageAsImage(final int size) {
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(new Color(0,0,0,0));
        g2d.fillRect(0, 0, size, size);
        g2d.dispose();

        return image;
    }

    private static
    File getErrorImage(final String cacheName) {
        try {
            final File save = CacheUtil.save(cacheName, ImageUtils.class.getResource("error_32.png"));
            // since it's the error file, we want to delete it on exit!
            save.deleteOnExit();
            return save;
        } catch (IOException e) {
            throw new RuntimeException("Serious problems! Unable to extract error image, this should NEVER happen!", e);
        }
    }

    private static
    File getIfCachedOrError(final String cacheName) {
        try {
            final File check = CacheUtil.check(cacheName);
            if (check != null) {
                return check;
            }
        } catch (IOException e) {
            SystemTray.logger.error("Error checking cache for information. Using error icon instead", e);
            return getErrorImage(cacheName);
        }
        return null;
    }

    public static synchronized
    File resizeAndCache(final int size, final File file) {
        return resizeAndCache(size, file.getAbsolutePath());
    }

    public static synchronized
    File resizeAndCache(final int size, final String fileName) {
        if (fileName == null) {
            return null;
        }

        try {
            FileInputStream fileInputStream = new FileInputStream(fileName);
            File file = resizeAndCache(size, fileInputStream);
            fileInputStream.close();

            return file;
        } catch (IOException e) {
            // have to serve up the error image instead.
            SystemTray.logger.error("Error reading image. Using error icon instead", e);
            return getErrorImage(size + "default");
        }
    }

    @SuppressWarnings("Duplicates")
    public static synchronized
    File resizeAndCache(final int size, final URL imageUrl) {
        if (imageUrl == null) {
            return null;
        }

        try {
            InputStream inputStream = imageUrl.openStream();
            File file = resizeAndCache(size, inputStream);
            inputStream.close();

            return file;
        } catch (IOException e) {
            // have to serve up the error image instead.
            SystemTray.logger.error("Error reading image. Using error icon instead", e);
            return getErrorImage(size + "default");
        }
    }
    @SuppressWarnings("Duplicates")
    public static synchronized
    File resizeAndCache(final int size, final Image image) {
        if (image == null) {
            return null;
        }

        // stupid java won't scale it right away, so we have to do this twice to get the correct size
        final Image trayImage = new ImageIcon(image).getImage();
        trayImage.flush();

        try {
            ImageInputStream imageInputStream = ImageIO.createImageInputStream(image);
            File file = resizeAndCache(size, imageInputStream);
            imageInputStream.close();

            return file;
        } catch (IOException e) {
            // have to serve up the error image instead.
            SystemTray.logger.error("Error reading image. Using error icon instead", e);
            return getErrorImage(size + "default");
        }
    }

    @SuppressWarnings("Duplicates")
    public static synchronized
    File resizeAndCache(final int size, final ImageInputStream imageStream) {
        if (imageStream == null) {
            return null;
        }

        try {
            ByteArrayOutputStream byteArrayOutputStream = IO.copyStream(imageStream);
            return resizeAndCache(size, new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
        } catch (IOException e) {
            // have to serve up the error image instead.
            SystemTray.logger.error("Error reading image. Using error icon instead", e);
            return getErrorImage(size + "default");
        }
    }

    @SuppressWarnings("Duplicates")
    public static synchronized
    File resizeAndCache(final int size, InputStream imageStream) {
        if (imageStream == null) {
            return null;
        }

        if (!(imageStream instanceof ByteArrayInputStream)) {
            // have to make a copy of the inputStream, but only if necessary
            try {
                ByteArrayOutputStream byteArrayOutputStream = IO.copyStream(imageStream);
                imageStream.close();

                imageStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException("Unable to read from inputStream.", e);
            }
        }
        imageStream.mark(0);

        // check if we already have this file information saved to disk, based on size + hash of data
        final String cacheName = size + "_" + CacheUtil.createNameAsHash(imageStream);
        ((ByteArrayInputStream) imageStream).reset();  // casting to avoid unnecessary try/catch for IOException


        // if we already have this fileName, reuse it
        final File check = getIfCachedOrError(cacheName);
        if (check != null) {
            return check;
        }

        // no cached file, so we resize then save the new one.
        boolean needsResize = true;
        try {
            imageStream.mark(0);
            Dimension imageSize = getImageSize(imageStream);
            //noinspection NumericCastThatLosesPrecision
            if (size == ((int) imageSize.getWidth()) && size == ((int) imageSize.getHeight())) {
                // we can reuse this URL (it's the correct size).
                needsResize = false;
            }
        } catch (IOException e) {
            // have to serve up the error image instead.
            SystemTray.logger.error("Error resizing image. Using error icon instead", e);
            return getErrorImage(cacheName);
        } finally {
            ((ByteArrayInputStream) imageStream).reset();  // casting to avoid unnecessary try/catch for IOException
        }

        if (needsResize) {
            // we have to hop through hoops.
            try {
                File resizedFile = resizeFileNoCheck(size, imageStream);

                // now cache that file
                try {
                    return CacheUtil.save(cacheName, resizedFile);
                } catch (IOException e) {
                    // have to serve up the error image instead.
                    SystemTray.logger.error("Error caching image. Using error icon instead", e);
                    return getErrorImage(cacheName);
                }

            } catch (IOException e) {
                // have to serve up the error image instead.
                SystemTray.logger.error("Error resizing image. Using error icon instead", e);
                return getErrorImage(cacheName);
            }

        } else {
            // no resize necessary, just cache as is.
            try {
                return CacheUtil.save(cacheName, imageStream);
            } catch (IOException e) {
                // have to serve up the error image instead.
                SystemTray.logger.error("Error caching image. Using error icon instead", e);
                return getErrorImage(cacheName);
            }
        }
    }

    /**
     * Resizes the given URL to the specified size. No checks are performed if it's the correct size to begin with.
     *
     * @return the file on disk that is the resized icon
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static
    File resizeFileNoCheck(final int size, final URL imageUrl) throws IOException {
        String extension = FileUtil.getExtension(imageUrl.getPath());
        if (extension.equals("")) {
            extension = "png"; // made up
        }

        InputStream inputStream = imageUrl.openStream();

        // have to resize the file (and return the new path)

        // now have to resize this file.
        File newFile = new File(TEMP_DIR, "temp_resize." + extension).getAbsoluteFile();
        Image image;


        // resize the image, keep aspect
        image = new ImageIcon(ImageIO.read(inputStream)).getImage().getScaledInstance(size, -1, Image.SCALE_SMOOTH);
        image.flush();

        // have to do this twice, so that it will finish loading the image (weird callback stuff is required if we don't do this)
        image = new ImageIcon(image).getImage();
        image.flush();

        // make whatever dirs we need to.
        newFile.getParentFile().mkdirs();

        // if it's already there, we have to delete it
        newFile.delete();

        // now write out the new one
        BufferedImage bufferedImage = getBufferedImage(image);
        ImageIO.write(bufferedImage, extension, newFile);

        return newFile;
    }

    /**
     * Resizes the given InputStream to the specified size. No checks are performed if it's the correct size to begin with.
     *
     * @return the file on disk that is the resized icon
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static
    File resizeFileNoCheck(final int size, InputStream inputStream) throws IOException {
        // have to resize the file (and return the new path)

        // now have to resize this file.
        File newFile = new File(TEMP_DIR, "temp_resize.png").getAbsoluteFile();
        Image image;


        // resize the image, keep aspect
        image = new ImageIcon(ImageIO.read(inputStream)).getImage().getScaledInstance(size, -1, Image.SCALE_SMOOTH);
        image.flush();

        // have to do this twice, so that it will finish loading the image (weird callback stuff is required if we don't do this)
        image = new ImageIcon(image).getImage();
        image.flush();

        // make whatever dirs we need to.
        newFile.getParentFile().mkdirs();

        // if it's already there, we have to delete it
        newFile.delete();

        // now write out the new one
        BufferedImage bufferedImage = getBufferedImage(image);
        ImageIO.write(bufferedImage, "png", newFile); // made up extension

        return newFile;
    }


    /**
     * Resizes the image (as a FILE on disk, or as a RESOURCE name), saves it as a file on disk. This file will be OVER-WRITTEN by any
     * operation that calls this method.
     *
     * @return the file string on disk that is the resized icon
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static
    String resizeFile(final int size, final String fileName) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(fileName);

        Dimension imageSize = getImageSize(fileInputStream);
        //noinspection NumericCastThatLosesPrecision
        if (size == ((int) imageSize.getWidth()) && size == ((int) imageSize.getHeight())) {
            // we can reuse this file.
            return fileName;
        }

        // have to resize the file (and return the new path)

        String extension = FileUtil.getExtension(fileName);
        if (extension.equals("")) {
            extension = "png"; // made up
        }

        // now have to resize this file.
        File newFile = new File(TEMP_DIR, "temp_resize." + extension).getAbsoluteFile();
        Image image;

        // is file sitting on drive
        File iconTest = new File(fileName);
        if (iconTest.isFile() && iconTest.canRead()) {
            final String absolutePath = iconTest.getAbsolutePath();

            // resize the image, keep aspect
            image = new ImageIcon(absolutePath).getImage().getScaledInstance(size, -1, Image.SCALE_SMOOTH);
            image.flush();
        }
        else {
            // suck it out of a URL/Resource (with debugging if necessary)
            final URL systemResource = LocationResolver.getResource(fileName);

            // resize the image, keep aspect
            image = new ImageIcon(systemResource).getImage().getScaledInstance(size, -1, Image.SCALE_SMOOTH);
            image.flush();
        }

        // have to do this twice, so that it will finish loading the image (weird callback stuff is required if we don't do this)
        image = new ImageIcon(image).getImage();
        image.flush();

        // make whatever dirs we need to.
        newFile.getParentFile().mkdirs();

        // if it's already there, we have to delete it
        newFile.delete();


        // now write out the new one
        BufferedImage bufferedImage = getBufferedImage(image);
        ImageIO.write(bufferedImage, extension, newFile);

        return newFile.getAbsolutePath();
    }

    private static
    BufferedImage getBufferedImage(Image image) {
        if (image instanceof BufferedImage) {
            return (BufferedImage) image;
        }

        BufferedImage bimage = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);

        Graphics2D bGr = bimage.createGraphics();
        bGr.addRenderingHints(new RenderingHints(RenderingHints.KEY_RENDERING,
                                                 RenderingHints.VALUE_RENDER_QUALITY));
        bGr.drawImage(image, 0, 0, null);
        bGr.dispose();

        // Return the buffered image
        return bimage;
    }


    /**
     * Reads the image size information from the specified file, without loading the entire file.
     *
     * @param fileStream the input stream of the file
     *
     * @return the image size dimensions. IOException if it could not be read
     */
    private static
    Dimension getImageSize(InputStream fileStream) throws IOException {
        ImageInputStream in = null;
        ImageReader reader = null;
        try {
            in = ImageIO.createImageInputStream(fileStream);

            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                reader = readers.next();
                reader.setInput(in);

                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            }
        } finally {
            // `ImageInputStream` is not a closeable in 1.6, so we do this manually.
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }

            if (reader != null) {
                reader.dispose();
            }
        }

        throw new IOException("Unable to read file inputStream for image size data.");
    }
}
