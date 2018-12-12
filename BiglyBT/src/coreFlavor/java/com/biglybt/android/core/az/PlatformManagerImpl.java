/*
 * Copyright (c) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.core.az;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.biglybt.android.client.AndroidUtils;
import com.biglybt.android.client.BiglyBTApp;
import com.biglybt.core.Core;
import com.biglybt.core.util.SystemProperties;
import com.biglybt.pif.platform.PlatformManagerException;
import com.biglybt.platform.*;

import android.os.Environment;
import android.util.Log;

public class PlatformManagerImpl
	implements PlatformManager
{
	private final Set<PlatformManagerCapabilities> capabilities = new HashSet<>();

	public PlatformManagerImpl() {
		capabilities.add(PlatformManagerCapabilities.GetUserDataDirectory);
	}

	public int getPlatformType() {
		return (PT_OTHER);
	}

	public String getVersion()

			throws PlatformManagerException {
		return ("1");
	}

	public boolean hasCapability(PlatformManagerCapabilities capability) {
		return (capabilities.contains(capability));
	}

	public boolean isAdditionalFileTypeRegistered(String name, String type)

			throws PlatformManagerException {
		return (true);
	}

	public void registerAdditionalFileType(String name, String description,
			String type, String content_type)

			throws PlatformManagerException {
	}

	public void unregisterAdditionalFileType(String name, String type)

			throws PlatformManagerException {
	}

	public void showFile(String file_name)

			throws PlatformManagerException {
		unsupported();
	}

	public File getLocation(long location_id)

			throws PlatformManagerException {
		if (location_id == LOC_USER_DATA) {

			return (new File(getUserDataDirectory()));

		} else if (location_id == LOC_DOWNLOADS) {

			return new File("/storage/5C09-33D9/NVIDIA_SHIELD/Series");
		} else if (location_id == LOC_DOCUMENTS) {

			return new File("/storage/5C09-33D9/NVIDIA_SHIELD/Series");
		} else if (location_id == LOC_MUSIC) {

			return Environment.getExternalStoragePublicDirectory(
					Environment.DIRECTORY_MUSIC);
		} else if (location_id == LOC_VIDEO) {

			return Environment.getExternalStoragePublicDirectory(
					Environment.DIRECTORY_MOVIES);

		} else {

			return (null);
		}
	}

	public String getComputerName() {
		return BiglyBTApp.deviceName;
	}

	public void startup(Core core)

			throws PlatformManagerException {
	}

	public String getUserDataDirectory()

			throws PlatformManagerException {
		return (SystemProperties.getApplicationPath());
	}

	public boolean isApplicationRegistered()

			throws PlatformManagerException {
		return (true);
	}

	public void registerApplication()

			throws PlatformManagerException {
	}

	public String getApplicationCommandLine()

			throws PlatformManagerException {
		unsupported();

		return (null);
	}

	public File getVMOptionFile()

			throws PlatformManagerException {
		unsupported();

		return (null);
	}

	public String[] getExplicitVMOptions()

			throws PlatformManagerException {
		unsupported();

		return (null);
	}

	public void setExplicitVMOptions(String[] options)

			throws PlatformManagerException {
	}

	public boolean getRunAtLogin()

			throws PlatformManagerException {
		unsupported();

		return (false);
	}

	public void setRunAtLogin(boolean run)

			throws PlatformManagerException {
		unsupported();
	}

	public int getShutdownTypes() {
		return (0);
	}

	public void shutdown(int type)

			throws PlatformManagerException {
		unsupported();
	}

	public void setPreventComputerSleep(boolean prevent_it)

			throws PlatformManagerException {

	}

	public boolean getPreventComputerSleep() {
		return (false);
	}

	public void createProcess(String command_line, boolean inherit_handles)

			throws PlatformManagerException {

	}

	public void performRecoverableFileDelete(String file_name)

			throws PlatformManagerException {

	}

	public void setTCPTOSEnabled(boolean enabled)

			throws PlatformManagerException {

	}

	public void copyFilePermissions(String from_file_name, String to_file_name)

			throws PlatformManagerException {

	}

	public boolean testNativeAvailability(String name)

			throws PlatformManagerException {
		unsupported();

		return (false);
	}

	public void traceRoute(InetAddress interface_address, InetAddress target,
			PlatformManagerPingCallback callback)

			throws PlatformManagerException {
		unsupported();
	}

	public void ping(InetAddress interface_address, InetAddress target,
			PlatformManagerPingCallback callback)

			throws PlatformManagerException {
		unsupported();
	}

	public int getMaxOpenFiles()

			throws PlatformManagerException {
		unsupported();

		return (0);
	}

	public void dispose() {
	}

	public void addListener(PlatformManagerListener listener) {
	}

	public void removeListener(PlatformManagerListener listener) {
	}

	public void requestUserAttention(int type, Object data)

			throws PlatformManagerException {
		unsupported();
	}

	public Class<?> loadClass(ClassLoader loader, String class_name)

			throws PlatformManagerException {
		try {
			String dex_path = "";

			if (loader instanceof URLClassLoader) {

				URLClassLoader ucl = (URLClassLoader) loader;

				URL[] urls = ucl.getURLs();

				if (AndroidUtils.DEBUG) {
					Log.i("Core", class_name + "] urls=" + Arrays.toString(urls));
				}

				for (URL u : urls) {

					File f = new File(u.toURI());

					if (f.exists()) {

						dex_path += (dex_path.length() == 0 ? "" : File.separator)
								+ f.getAbsolutePath();

					} else {

						dex_path = "";

						Log.e("Core", "Can't resolve url '" + u + "'");

						break;
					}
				}
			} else {
				if (AndroidUtils.DEBUG) {
					Log.d("Core",
							class_name + " load is " + loader.getClass().getSimpleName());
				}
			}

			if (dex_path.length() > 0 && AndroidUtils.DEBUG) {
				Log.w("Core", class_name + "] URLClassLoader; dex_path=" + dex_path);
			}

			return loader.loadClass(class_name);

		} catch (Throwable e) {

			throw new PlatformManagerException("load of '" + class_name + "' failed",
					e);
		}
	}

	private static void unsupported()

			throws PlatformManagerException {
		throw (new PlatformManagerException("Unsupported"));
	}
}
