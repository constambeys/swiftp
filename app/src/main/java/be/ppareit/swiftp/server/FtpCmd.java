/*
Copyright 2009 David Revell

This file is part of SwiFTP.

SwiFTP is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

SwiFTP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with SwiFTP.  If not, see <http://www.gnu.org/licenses/>.
 */

package be.ppareit.swiftp.server;

import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.lang.reflect.Constructor;

import be.ppareit.swiftp.Util;
import be.ppareit.swiftp.utils.FileUtil;

public abstract class FtpCmd implements Runnable {
    private static final String TAG = FtpCmd.class.getSimpleName();

    protected SessionThread sessionThread;

    protected static CmdMap[] cmdClasses = { new CmdMap("SYST", CmdSYST.class),
            new CmdMap("USER", CmdUSER.class), new CmdMap("PASS", CmdPASS.class),
            new CmdMap("TYPE", CmdTYPE.class), new CmdMap("CWD", CmdCWD.class),
            new CmdMap("PWD", CmdPWD.class), new CmdMap("LIST", CmdLIST.class),
            new CmdMap("PASV", CmdPASV.class), new CmdMap("RETR", CmdRETR.class),
            new CmdMap("NLST", CmdNLST.class), new CmdMap("NOOP", CmdNOOP.class),
            new CmdMap("STOR", CmdSTOR.class), new CmdMap("DELE", CmdDELE.class),
            new CmdMap("RNFR", CmdRNFR.class), new CmdMap("RNTO", CmdRNTO.class),
            new CmdMap("RMD", CmdRMD.class), new CmdMap("MKD", CmdMKD.class),
            new CmdMap("OPTS", CmdOPTS.class), new CmdMap("PORT", CmdPORT.class),
            new CmdMap("QUIT", CmdQUIT.class), new CmdMap("FEAT", CmdFEAT.class),
            new CmdMap("SIZE", CmdSIZE.class), new CmdMap("CDUP", CmdCDUP.class),
            new CmdMap("APPE", CmdAPPE.class), new CmdMap("XCUP", CmdCDUP.class), // synonym
            new CmdMap("XPWD", CmdPWD.class), // synonym
            new CmdMap("XMKD", CmdMKD.class), // synonym
            new CmdMap("XRMD", CmdRMD.class), // synonym
            new CmdMap("MDTM", CmdMDTM.class), //
            new CmdMap("MFMT", CmdMFMT.class), //
            new CmdMap("REST", CmdREST.class), //
            new CmdMap("SITE", CmdSITE.class), //
            new CmdMap("MLST", CmdMLST.class), //
            new CmdMap("MLSD", CmdMLSD.class), //
            new CmdMap("HASH", CmdHASH.class),
            new CmdMap("RANG", CmdRANG.class)
    };

    private static Class<?>[] allowedCmdsWhileAnonymous = { CmdUSER.class, CmdPASS.class, //
            CmdCWD.class, CmdLIST.class, CmdMDTM.class, CmdNLST.class, CmdPASV.class, //
            CmdPWD.class, CmdQUIT.class, CmdRETR.class, CmdSIZE.class, CmdTYPE.class, //
            CmdCDUP.class, CmdNOOP.class, CmdSYST.class, CmdPORT.class, //
            CmdMLST.class, CmdMLSD.class, CmdHASH.class, CmdRANG.class //
    };

    public FtpCmd(SessionThread sessionThread) {
        this.sessionThread = sessionThread;
    }

    @Override
    abstract public void run();

    protected static void dispatchCommand(SessionThread session, String inputString) {
        String[] strings = inputString.split(" ");
        String unrecognizedCmdMsg = "502 Command not recognized\r\n";
        if (strings == null) {
            // There was some egregious sort of parsing error
            String errString = "502 Command parse error\r\n";
            Log.d(TAG, errString);
            session.writeString(errString);
            return;
        }
        if (strings.length < 1) {
            Log.d(TAG, "No strings parsed");
            session.writeString(unrecognizedCmdMsg);
            return;
        }
        String verb = strings[0];
        if (verb.length() < 1) {
            Log.i(TAG, "Invalid command verb");
            session.writeString(unrecognizedCmdMsg);
            return;
        }
        FtpCmd cmdInstance = null;
        verb = verb.trim();
        verb = verb.toUpperCase();
        for (int i = 0; i < cmdClasses.length; i++) {

            if (cmdClasses[i].getName().equals(verb)) {
                // We found the correct command. We retrieve the corresponding
                // Class object, get the Constructor object for that Class, and
                // and use that Constructor to instantiate the correct FtpCmd
                // subclass. Yes, I'm serious.
                Constructor<? extends FtpCmd> constructor;
                try {
                    constructor = cmdClasses[i].getCommand().getConstructor(
                            new Class[] { SessionThread.class, String.class });
                } catch (NoSuchMethodException e) {
                    Log.e(TAG, "FtpCmd subclass lacks expected " + "constructor ");
                    return;
                }
                try {
                    cmdInstance = constructor.newInstance(new Object[] { session,
                            inputString });
                } catch (Exception e) {
                    Log.e(TAG, "Instance creation error on FtpCmd");
                    return;
                }
            }
        }
        if (cmdInstance == null) {
            // If we couldn't find a matching command,
            Log.d(TAG, "Ignoring unrecognized FTP verb: " + verb);
            session.writeString(unrecognizedCmdMsg);
            return;
        }

        if (session.isUserLoggedIn()) {
            cmdInstance.run();
        } else if (session.isAnonymouslyLoggedIn() == true) {
            boolean validCmd = false;
            for (Class<?> cl : allowedCmdsWhileAnonymous) {
                if (cmdInstance.getClass().equals(cl)) {
                    validCmd = true;
                    break;
                }
            }
            if (validCmd == true) {
                cmdInstance.run();
            } else {
                session.writeString("530 Guest user is not allowed to use that command\r\n");
            }
        } else if (cmdInstance.getClass().equals(CmdUSER.class)
                || cmdInstance.getClass().equals(CmdPASS.class)
                || cmdInstance.getClass().equals(CmdQUIT.class)) {
            cmdInstance.run();
        } else {
            session.writeString("530 Login first with USER and PASS, or QUIT\r\n");
        }
    }

    /**
     * An FTP parameter is that part of the input string that occurs after the first
     * space, including any subsequent spaces. Also, we want to chop off the trailing
     * '\r\n', if present.
     *
     * Some parameters shouldn't be logged or output (e.g. passwords), so the caller can
     * use silent==true in that case.
     */
    static public String getParameter(String input, boolean silent) {
        if (input == null) {
            return "";
        }
        int firstSpacePosition = input.indexOf(' ');
        if (firstSpacePosition == -1) {
            return "";
        }
        String retString = input.substring(firstSpacePosition + 1);

        // Remove trailing whitespace
        // todo: trailing whitespace may be significant, just remove \r\n
        retString = retString.replaceAll("\\s+$", "");

        if (!silent) {
            Log.d(TAG, "Parsed argument: " + retString);
        }
        return retString;
    }

    /**
     * A wrapper around getParameter, for when we don't want it to be silent.
     */
    static public String getParameter(String input) {
        return getParameter(input, false);
    }

    public static File inputPathToChrootedFile(final File chrootDir, final File existingPrefix,
                                               String param, final boolean isDirOnly) {
        if (Util.useScopedStorage()) {
            // NEW WAY
            // Chroot is *1 tree & including eg *2 "/storage" that the tree doesn't contain.
            // *3 The rest is provided by the client.
            // *4 The param when with file is with *3 so we need to remove path from param when it is.
            // *5 All that's left is to tack on the client provided path to the end of the tree.
            // There are multiple conflicting oddities that happen and are dealt with here.
            if (isDirOnly && !param.startsWith(File.separator)) param = File.separator + param;
            // May be empty at times and param will instead have it.
            String sessionClientPath = FileUtil.getScopedClientPath(param, existingPrefix, null);
            // Get the full chroot path including storage.
            Uri uri = FileUtil.getTreeUri();
            DocumentFile df = FileUtil.getDocumentFileFromUri(uri);
            final String tree = FileUtil.getUriStoragePathFullFromDocumentFile(df, "");
            if (tree == null) return new File(""); // That's bad. Make following checks fail.
            // Deal with param and client specified paths.
            String paramClientPath = "";
            if (!isDirOnly && param.contains(File.separator)) {
                final int lastSlash = param.lastIndexOf(File.separator);
                paramClientPath = param.substring(0, lastSlash);
                // Can't have it in there with the new code. That's a conflict!
                param = param.substring(lastSlash + 1);
                if (!paramClientPath.startsWith(File.separator)) {
                    // Keep it the same to avoid problems.
                    paramClientPath = File.separator + paramClientPath;
                }
            } else if (isDirOnly && param.contains(File.separator)) {
                // To make things worse, the param can also be a full path such as in FileZilla and
                // using its tree to jump randomly anywhere. Here, the param already contains the
                // entire path needed. And then sometimes it doesn't have the entire path lol :)
                if (param.contains(tree)) {
                    return new File(param);
                }
            }
            String mPath = "";
            // To make it worse, param can be dir and have no file and no slash lol. So...
            // added isDirOnly in order to know what the calling method is working on as that can tell us.
            if (isDirOnly) {
                if (!sessionClientPath.isEmpty() && !sessionClientPath.equals(param)) {
                    // Varoious fixes and checks of random ways it can do as seen on one device and internal
                    if (param.startsWith(File.separator) && !tree.startsWith(File.separator)) {
                        param = param.replaceFirst(File.separator, "");
                    } else if (!param.startsWith(File.separator) && tree.startsWith(File.separator)) {
                        param = File.separator + param;
                    }
                    if (param.endsWith(File.separator) && !tree.endsWith(File.separator)) {
                        param = param.substring(0, param.length() - 1);
                    } else if (!param.endsWith(File.separator) && tree.endsWith(File.separator)) {
                        param += File.separator;
                    }
                    if (!param.equals(tree)) {
                        mPath = sessionClientPath + File.separator + param;
                    }
                } else {
                    mPath = param;
                }
            } else {
                if (!sessionClientPath.isEmpty() && paramClientPath.isEmpty())
                    mPath = sessionClientPath;
                else if (sessionClientPath.isEmpty() && !paramClientPath.isEmpty())
                    mPath = paramClientPath;
                else {
                    if (sessionClientPath.equals(paramClientPath)) mPath = sessionClientPath;
                    else mPath = paramClientPath;
                }
            }
            // Various checks and fixes as to ways it could go as seen on one device and internal
            if (!mPath.startsWith(File.separator) && !tree.endsWith(File.separator)) {
                mPath = File.separator + mPath;
            } else if (mPath.startsWith(File.separator) && tree.endsWith(File.separator)) {
                mPath = mPath.replaceFirst(File.separator, "");
            }
            // Finally get the full current path
            String path = "";
            if (!mPath.contains(tree)) path = tree + mPath;
            // Let's end this.
            if (!isDirOnly){
                return new File(path, param);
            }
            if (sessionClientPath.equals(param)) {
                final String doubleVision = sessionClientPath + param;
                if (!path.endsWith(doubleVision)) {
                    // Correct double vision; permutation fix
                    return new File(path, param);
                }
            }
            // Another possible end
            return new File(path);
        }
        // OLD WAY (with issues and not compatible with the new way.)
        try {
            if (param.charAt(0) == '/') {
                // The STOR contained an absolute path
                return new File(chrootDir, param);
            }
        } catch (Exception e) {
        }

        // The STOR contained a relative path
        return new File(existingPrefix, param);
    }

    public boolean violatesChroot(File file) {
        try {
            // taking the canonical path as new devices have sdcard symbolic linked
            // for multi user support
            File chroot = sessionThread.getChrootDir();
            String canonicalChroot = chroot.getCanonicalPath();
            String canonicalPath = file.getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalChroot)) {
                Log.i(TAG, "Path violated folder restriction, denying");
                Log.d(TAG, "path: " + canonicalPath);
                Log.d(TAG, "chroot: " + chroot.toString());
                return true; // the path must begin with the chroot path
            }
            return false;
        } catch (Exception e) {
            Log.i(TAG, "Path canonicalization problem: " + e.toString());
            if (file != null) Log.i(TAG, "When checking file: " + file.getAbsolutePath()); // fix possible crash
            return true; // for security, assume violation
        }
    }

    public boolean violatesChroot(DocumentFile file, String param) {
        try {
            // Get the full path to the chosen Android 11 dir and compare with that of the file
            File chroot = sessionThread.getChrootDir();
            String canonicalChroot = chroot.getCanonicalPath();
            final String path = FileUtil.getScopedClientPath(param, null, null);
            String canonicalPath = FileUtil.getUriStoragePathFullFromDocumentFile(file, path);

            if (canonicalPath == null || !canonicalPath.startsWith(canonicalChroot)) {
                Log.i(TAG, "Path violated folder restriction, denying");
                Log.d(TAG, "path: " + canonicalPath);
                Log.d(TAG, "chroot: " + chroot.toString());
                return true; // the path must begin with the chroot path
            }
            return false;
        } catch (Exception e) {
            Log.i(TAG, "Path canonicalization problem: " + e.toString());
            //Log.i(TAG, "When checking file: " + file.getAbsolutePath());
            return true; // for security, assume violation
        }
    }
}
