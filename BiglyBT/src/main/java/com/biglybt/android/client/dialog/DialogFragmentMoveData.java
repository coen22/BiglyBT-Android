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

package com.biglybt.android.client.dialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.biglybt.android.client.*;
import com.biglybt.android.client.AndroidUtilsUI.AlertDialogBuilder;
import com.biglybt.android.client.session.*;
import com.biglybt.android.util.FileUtils;
import com.biglybt.android.util.FileUtils.PathInfo;
import com.biglybt.android.util.MapUtils;
import com.biglybt.android.util.PaulBurkeFileUtils;
import com.biglybt.util.DisplayFormatters;
import com.biglybt.util.Thunk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.*;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.ViewGroup.LayoutParams;
import android.widget.*;

public class DialogFragmentMoveData
	extends DialogFragmentResized
{
	private static final String TAG = "MoveDataDialog";

	private static final String KEY_HISTORY = "history";

	private static final String BUNDLEKEY_DEF_APPEND_NAME = "DefAppendName";

	public final static int REQUEST_PATHCHOOSER = 3;

	@Thunk
	EditText etLocation;

	private CheckBox cbRememberLocation;

	private long torrentId;

	private ArrayList<String> history;

	@Thunk
	AlertDialog dialog;

	private AlertDialogBuilder alertDialogBuilder;

	public interface DialogFragmentMoveDataListener
	{
		void locationChanged(String location);
	}

	private CheckBox cbAppendSubDir;

	private String torrentName;

	@Thunk
	String currentDownloadDir;

	private boolean appendName;

	private boolean isLocalCore;

	@LayoutRes
	private int layoutID;

	@Thunk
	String newLocation;

	public DialogFragmentMoveData() {
		setMinWidthPX(
				(int) (AndroidUtilsUI.getScreenWidthPx(BiglyBTApp.getContext()) * 0.9));
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (isLocalCore) {
			return;
		}

		boolean checked = cbRememberLocation != null
				&& cbRememberLocation.isChecked();
		boolean checkedSub = cbAppendSubDir != null && cbAppendSubDir.isChecked();
		String location = etLocation.getText().toString();

		// This mess is an attempt to rebuild the layout within the dialog
		// when the orientation changes.  Seems to work, but doesn't make sense
		ViewGroup viewGroup = (ViewGroup) alertDialogBuilder.view;
		//ViewGroup parent = (ViewGroup) viewGroup.getParent();
		viewGroup.removeAllViews();
		View view = View.inflate(dialog.getContext(), layoutID, viewGroup);
		dialog.setView(view);
		alertDialogBuilder = new AlertDialogBuilder(view,
				alertDialogBuilder.builder);
		setupWidgets(view);

		if (cbRememberLocation != null) {
			cbRememberLocation.setChecked(checked);
		}
		if (etLocation != null) {
			etLocation.setText(location);
		}
		if (cbAppendSubDir != null) {
			cbAppendSubDir.setChecked(checkedSub);
		}

		resize();
	}

	private void resize() {
		if (isLocalCore) {
			return;
		}
		// fill full width because we need all the room
		DisplayMetrics metrics = getResources().getDisplayMetrics();
		if (metrics == null) {
			return;
		}
		Dialog dialog = getDialog();
		if (dialog == null) {
			return;
		}
		Window window = dialog.getWindow();
		if (window == null) {
			return;
		}
		try {
			window.setLayout(metrics.widthPixels, LayoutParams.WRAP_CONTENT);
		} catch (NullPointerException ignore) {
		}

		WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
		lp.copyFrom(window.getAttributes());
		lp.width = metrics.widthPixels; // WindowManager.LayoutParams.MATCH_PARENT;
		lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
		window.setAttributes(lp);

	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		Bundle args = getArguments();
		assert args != null;
		torrentName = args.getString(TransmissionVars.FIELD_TORRENT_NAME);
		torrentId = args.getLong(TransmissionVars.FIELD_TORRENT_ID);
		currentDownloadDir = "/storage/5C09-33D9/NVIDIA_SHIELD/Series";
		appendName = args.getBoolean(BUNDLEKEY_DEF_APPEND_NAME, true);
		history = args.getStringArrayList(KEY_HISTORY);

		Session session = SessionManager.findOrCreateSession(this, null);
		if (session == null) {
			AnalyticsTracker.getInstance(this).logError("session null", TAG);
			return super.onCreateDialog(savedInstanceState);
		}
		isLocalCore = session.getRemoteProfile().getRemoteType() == RemoteProfile.TYPE_CORE;

		layoutID = isLocalCore ? R.layout.dialog_move_localdata
				: R.layout.dialog_move_data;

		alertDialogBuilder = AndroidUtilsUI.createAlertDialogBuilder(getActivity(),
				layoutID);

		AlertDialog.Builder builder = alertDialogBuilder.builder;

		builder.setTitle(R.string.action_sel_relocate);

		// Add action buttons
		builder.setPositiveButton(android.R.string.ok, (dialog, id) -> moveData());
		builder.setNegativeButton(android.R.string.cancel,
				(dialog, id) -> DialogFragmentMoveData.this.getDialog().cancel());

		dialog = builder.create();
		setupWidgets(alertDialogBuilder.view);

		return dialog;
	}

	@Override
	public void onStart() {
		super.onStart();
		Session session = SessionManager.findOrCreateSession(this, null);
		if (session == null) {
			this.dismissAllowingStateLoss();
		}
	}

	@Thunk
	void moveData() {
		Session session = SessionManager.findOrCreateSession(this, null);
		if (session == null) {
			return;
		}

		String moveTo = etLocation == null ? newLocation
				: etLocation.getText().toString();
		if (cbRememberLocation != null && cbRememberLocation.isChecked()) {
			if (history != null && !history.contains(moveTo)) {
				history.add(0, moveTo);
				session.moveDataHistoryChanged(history);
			}
		}
		if (cbAppendSubDir != null && cbAppendSubDir.isChecked()) {
			char sep = moveTo.length() > 2 && moveTo.charAt(2) == '\\' ? '\\' : '/';
			moveTo += sep + torrentName;
		}
		session.torrent.moveDataTo(torrentId, moveTo);
		FragmentActivity activity = getActivity();
		if (activity instanceof DialogFragmentMoveDataListener) {
			((DialogFragmentMoveDataListener) activity).locationChanged(moveTo);
		}
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		resize();
	}

	@Override
	public void onResume() {
		super.onResume();

		ListView lvAvailPaths = dialog.findViewById(R.id.movedata_avail_paths);
		if (lvAvailPaths != null) {
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(
					newLocation != null);
		}

		resize();
	}

	private void setupWidgets(View view) {
		Resources resources = getResources();
		Context context = getContext();
		if (currentDownloadDir != null
				&& currentDownloadDir.endsWith(torrentName)) {
			currentDownloadDir = currentDownloadDir.substring(0,
					currentDownloadDir.length() - torrentName.length() - 1);
			appendName = true;
		}

		ArrayList<String> newHistory = history == null ? new ArrayList<>(1)
				: new ArrayList<>(history);

		if (currentDownloadDir != null
				&& !newHistory.contains(currentDownloadDir)) {
			if (newHistory.size() > 1) {
				newHistory.add(1, currentDownloadDir);
			} else {
				newHistory.add(currentDownloadDir);
			}
		}

		etLocation = view.findViewById(R.id.movedata_editview);
		if (currentDownloadDir != null && etLocation != null) {
			etLocation.setText(currentDownloadDir);
		}

		ImageButton btnBrowser = view.findViewById(R.id.movedata_btn_editdir);
		if (btnBrowser != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
					&& isLocalCore) {

				btnBrowser.setOnClickListener(
						v -> FileUtils.openFolderChooser(DialogFragmentMoveData.this,
								currentDownloadDir, REQUEST_PATHCHOOSER));
			} else {
				btnBrowser.setVisibility(View.GONE);
			}
		}

		cbRememberLocation = view.findViewById(R.id.movedata_remember);

		TextView tv = view.findViewById(R.id.movedata_label);
		if (tv != null) {
			tv.setText(AndroidUtils.fromHTML(resources, R.string.movedata_label,
					torrentName));
		}

		tv = view.findViewById(R.id.movedata_currentlocation);
		if (tv != null) {
			CharSequence s = FileUtils.buildPathInfo(context,
					new File(currentDownloadDir)).getFriendlyName(context);

			tv.setText(AndroidUtils.fromHTML(resources,
					R.string.movedata_currentlocation, s));
		}

		cbAppendSubDir = view.findViewById(R.id.movedata_appendname);
		if (cbAppendSubDir != null) {
			cbAppendSubDir.setChecked(appendName);
			cbAppendSubDir.setText(AndroidUtils.fromHTML(resources,
					R.string.movedata_place_in_subfolder, torrentName));
		}

		ListView lvHistory = view.findViewById(R.id.movedata_historylist);
		if (lvHistory != null) {
			ArrayAdapter<String> adapter = new ArrayAdapter<>(view.getContext(),
					R.layout.list_view_small_font, newHistory);
			lvHistory.setAdapter(adapter);

			lvHistory.setOnItemClickListener((parent, view1, position, id) -> {
				Object item = parent.getItemAtPosition(position);

				if (item instanceof String) {
					etLocation.setText((String) item);
				}
			});
		}

		final ListView lvAvailPaths = view.findViewById(R.id.movedata_avail_paths);
		if (lvAvailPaths != null) {
			lvAvailPaths.setItemsCanFocus(true);

			new AsyncTask<View, Object, List<PathInfo>>() {
				View view;

				@Override
				protected List<PathInfo> doInBackground(View... views) {
					this.view = views[0];
					List<PathInfo> list = new ArrayList<>();

					Context context = view.getContext();
					Session session = SessionManager.findOrCreateSession(
							DialogFragmentMoveData.this, null);
					if (session == null) {
						return null;
					}
					SessionSettings sessionSettings = session.getSessionSettingsClone();
					if (sessionSettings == null) {
						return null;
					}
					String downloadDir = "/storage/5C09-33D9/NVIDIA_SHIELD/Series";
					if (downloadDir != null) {
						File file = new File(downloadDir);
						list.add(FileUtils.buildPathInfo(context, file));
					}

					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
						File[] externalFilesDirs = context.getExternalFilesDirs(null);
						for (File externalFilesDir : externalFilesDirs) {
							if (FileUtils.canWrite(externalFilesDir)) {
								list.add(FileUtils.buildPathInfo(context, externalFilesDir));
							}
						}
					}

					File externalStorageDirectory = new File("/storage/5C09-33D9/NVIDIA_SHIELD/Series");
					if (FileUtils.canWrite(externalStorageDirectory)) {
						list.add(
								FileUtils.buildPathInfo(context, externalStorageDirectory));
					}

					String secondaryStorage = System.getenv("SECONDARY_STORAGE"); //NON-NLS
					if (secondaryStorage != null) {
						String[] split = secondaryStorage.split(File.pathSeparator);
						for (String dir : split) {
							File f = new File(dir);
							if (FileUtils.canWrite(f)) {
								list.add(FileUtils.buildPathInfo(context, f));
							}
						}
					}

					String[] DIR_IDS = new String[] {
						"/storage/5C09-33D9/NVIDIA_SHIELD/Series",
						"Documents", //NON-NLS API19:	Environment.DIRECTORY_DOCUMENTS,
						Environment.DIRECTORY_MOVIES,
						Environment.DIRECTORY_MUSIC,
						Environment.DIRECTORY_PICTURES,
						Environment.DIRECTORY_PODCASTS
					};
					for (String id : DIR_IDS) {
						File directory = Environment.getExternalStoragePublicDirectory(id);
						if (FileUtils.canWrite(directory)) {
							list.add(FileUtils.buildPathInfo(context, directory));
						}
					}
					return list;
				}

				@Override
				protected void onPostExecute(List<PathInfo> list) {
					if (list == null) {
						return;
					}
					final PathArrayAdapter adapter = new PathArrayAdapter(
							view.getContext(), list);
					lvAvailPaths.setAdapter(adapter);
					lvAvailPaths.setItemsCanFocus(true);

					lvAvailPaths.setOnItemClickListener((parent, view, position, id) -> {

						dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
						PathInfo pathInfo = adapter.getItem(position);
						newLocation = pathInfo.file.getAbsolutePath();
						dialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
					});
				}
			}.execute(view);

		}
	}

	@SuppressWarnings("rawtypes")
	public static void openMoveDataDialog(Map mapTorrent, Session session,
			FragmentManager fm) {
		DialogFragmentMoveData dlg = new DialogFragmentMoveData();
		Bundle bundle = new Bundle();
		if (mapTorrent == null) {
			return;
		}

		bundle.putLong(TransmissionVars.FIELD_TORRENT_ID,
				MapUtils.getMapLong(mapTorrent, TransmissionVars.FIELD_TORRENT_ID, -1));
		bundle.putString(TransmissionVars.FIELD_TORRENT_NAME,
				"" + mapTorrent.get(TransmissionVars.FIELD_TORRENT_NAME));
		int numFiles = MapUtils.getMapInt(mapTorrent,
				TransmissionVars.FIELD_TORRENT_FILE_COUNT, 0);
		bundle.putBoolean(BUNDLEKEY_DEF_APPEND_NAME, numFiles > 1);
		bundle.putString(SessionManager.BUNDLE_KEY,
				session.getRemoteProfile().getID());

		SessionSettings sessionSettings = session.getSessionSettingsClone();

		String defaultDownloadDir = "/storage/5C09-33D9/NVIDIA_SHIELD/Series";
		String downloadDir = "/storage/5C09-33D9/NVIDIA_SHIELD/Series";
		bundle.putString(TransmissionVars.FIELD_TORRENT_DOWNLOAD_DIR, downloadDir);

		List<String> saveHistory = session.getRemoteProfile().getSavePathHistory();

		ArrayList<String> history = new ArrayList<>(saveHistory.size() + 1);
		if (defaultDownloadDir != null) {
			history.add(defaultDownloadDir);
		}

		for (String s : saveHistory) {
			if (!history.contains(s)) {
				history.add(s);
			}
		}
		bundle.putStringArrayList(KEY_HISTORY, history);
		dlg.setArguments(bundle);
		AndroidUtilsUI.showDialog(dlg, fm, TAG);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_PATHCHOOSER
				&& resultCode == Activity.RESULT_OK) {
			Uri uri = data.getData();
			if (uri != null) {
				String path = PaulBurkeFileUtils.getPath(getActivity(), uri);
				etLocation.setText(path == null ? uri.toString() : path);
			}
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	public class PathArrayAdapter
		extends ArrayAdapter<PathInfo>
	{
		private final Context context;

		public PathArrayAdapter(Context context, List<PathInfo> list) {
			super(context, R.layout.row_path_selection, list);
			this.context = context;
		}

		@NonNull
		@Override
		public View getView(int position, View rowView, @NonNull ViewGroup parent) {
			if (rowView == null) {
				LayoutInflater inflater = (LayoutInflater) context.getSystemService(
						Context.LAYOUT_INFLATER_SERVICE);
				rowView = inflater.inflate(R.layout.row_path_selection, parent, false);
			}
			TextView tvPath = rowView.findViewById(R.id.path_row_text);
			TextView tvFree = rowView.findViewById(R.id.path_row_free);
			ImageView ivPath = rowView.findViewById(R.id.path_row_image);

			final PathInfo item = getItem(position);
			if (item == null) {
				return rowView;
			}
			tvPath.setText(item.getFriendlyName(context));
			ivPath.setImageResource(
					item.isRemovable ? R.drawable.ic_sd_storage_gray_24dp
							: R.drawable.ic_folder_gray_24dp);
			String freeSpaceString = DisplayFormatters.formatByteCountToKiBEtc(
					item.file.getFreeSpace());
			String s = context.getResources().getString(R.string.x_space_free,
					freeSpaceString);
			tvFree.setText(s + " - " + item.storagePath);

			return rowView;
		}

	}
}
