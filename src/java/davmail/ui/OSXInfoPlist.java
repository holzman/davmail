/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2012  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.ui;

import davmail.util.IOUtil;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Handle OSX Info.plist file access
 */
public class OSXInfoPlist {
    protected static final Logger LOGGER = Logger.getLogger(OSXInfoPlist.class);
    protected static final String INFO_PLIST_PATH = "../Info.plist";

    private OSXInfoPlist() {
    }

    protected static boolean isOSX() {
        return System.getProperty("os.name").toLowerCase().startsWith("mac os x");
    }

    protected static String getInfoPlistContent() throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(getInfoPlistPath())) {
            return new String(IOUtil.readFully(fileInputStream), StandardCharsets.UTF_8);
        }
    }

    /**
     * Test current LSUIElement (hide from dock) value
     *
     * @return true if application is hidden from dock
     */
    public static boolean isHideFromDock() {
        boolean result = false;
        try {
            result = isOSX() && getInfoPlistContent().contains("<key>LSUIElement</key><string>1</string>");
        } catch (IOException e) {
            LOGGER.warn("Unable to update Info.plist", e);
        }
        return result;
    }

    /**
     * Update LSUIElement (hide from dock) value
     *
     * @param hideFromDock new hide from dock value
     */
    public static void setOSXHideFromDock(boolean hideFromDock) {
        try {
            if (isOSX()) {
                boolean currentHideFromDock = isHideFromDock();
                if (currentHideFromDock != hideFromDock) {
                    String content = getInfoPlistContent();
                    try (FileOutputStream fileOutputStream = new FileOutputStream(getInfoPlistPath())) {
                        fileOutputStream.write(content.replaceFirst(
                                "<key>LSUIElement</key><string>" + (currentHideFromDock ? "1" : "0") + "</string>",
                                "<key>LSUIElement</key><string>" + (hideFromDock ? "1" : "0") + "</string>").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Unable to update Info.plist", e);
        }
    }

    private static String getInfoPlistPath() throws IOException {
        File file = new File(INFO_PLIST_PATH);
        if (file.exists()) {
            return INFO_PLIST_PATH;
        }
        throw new IOException("Info.plist file not found");
    }
}
