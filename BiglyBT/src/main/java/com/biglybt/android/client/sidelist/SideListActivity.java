/*
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.client.sidelist;

import java.util.List;

import com.biglybt.android.adapter.SortableRecyclerAdapter;
import com.biglybt.android.client.AndroidUtils;
import com.biglybt.android.client.AndroidUtilsUI;
import com.biglybt.android.client.R;
import com.biglybt.android.client.activity.DrawerActivity;
import com.biglybt.util.Thunk;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

/**
 * <p>
 * A {@link DrawerActivity} that uses our custom SideList framework.
 * Doesn't need to have a drawer.
 * </p>
 * <p>
 * Created by TuxPaper on 8/15/18.
 */
public abstract class SideListActivity
	extends DrawerActivity
	implements SideListHelperListener
{
	private static final String TAG = "SideListActivity";

	@Thunk
	private SideListHelper sideListHelper;

	@Override
	public void onDrawerOpened(View view) {
		setupSideListArea(view);
	}

	private void setupSideListArea(View view) {
		SortableRecyclerAdapter mainAdapter = getMainAdapter();
		if (mainAdapter == null) {
			if (AndroidUtils.DEBUG) {
				log(Log.WARN, TAG, "setupSideListArea: No MainAdapter "
						+ AndroidUtils.getCompressedStackTrace());
			}
			if (sideListHelper != null) {
				sideListHelper.clear();
			}
			return;
		}

		if (sideListHelper == null || !sideListHelper.isValid()) {
			sideListHelper = new SideListHelper(this, this, R.id.sidelist_layout,
					mainAdapter, this);
			if (!sideListHelper.isValid()) {
				return;
			}

			sideListHelper.commonInit(view);
		} else {
			sideListHelper.setMainAdapter(mainAdapter);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item != null && item.getItemId() == android.R.id.home) {
			// Respond to the action bar's Up/Home button
			if (getDrawerLayout() == null && sideListHelper != null) {
				if (sideListHelper.flipExpandState()) {
					return true;
				}
			}
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (sideListHelper != null) {
			sideListHelper.onSaveInstanceState(outState);
		}
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		if (sideListHelper != null) {
			sideListHelper.onRestoreInstanceState(savedInstanceState);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();

		setupSideListArea(AndroidUtilsUI.getContentView(this));
	}

	@Override
	public void sideListExpandListChanged(boolean expanded) {
		List<Fragment> fragments = AndroidUtilsUI.getFragments(
				getSupportFragmentManager());
		for (Fragment f : fragments) {
			if (f instanceof SideListHelperListener) {
				((SideListHelperListener) f).sideListExpandListChanged(expanded);
			}
		}
	}

	@Override
	public void sideListExpandListChanging(boolean expanded) {
		List<Fragment> fragments = AndroidUtilsUI.getFragments(
				getSupportFragmentManager());
		for (Fragment f : fragments) {
			if (f instanceof SideListHelperListener) {
				((SideListHelperListener) f).sideListExpandListChanging(expanded);
			}
		}
	}

	@Override
	public void onSideListHelperPostSetup(SideListHelper sideListHelper) {
		List<Fragment> fragments = AndroidUtilsUI.getFragments(
				getSupportFragmentManager());
		for (Fragment f : fragments) {
			if (f instanceof SideListHelperListener) {
				((SideListHelperListener) f).onSideListHelperPostSetup(sideListHelper);
			}
		}
	}

	@Override
	public void onSideListHelperCreated(SideListHelper sideListHelper) {
		List<Fragment> fragments = AndroidUtilsUI.getFragments(
				getSupportFragmentManager());
		for (Fragment f : fragments) {
			if (f instanceof SideListHelperListener) {
				((SideListHelperListener) f).onSideListHelperCreated(sideListHelper);
			}
		}
	}

	@Override
	public void onSideListHelperVisibleSetup(View view) {
		List<Fragment> fragments = AndroidUtilsUI.getFragments(
				getSupportFragmentManager());
		for (Fragment f : fragments) {
			if (f instanceof SideListHelperListener) {
				((SideListHelperListener) f).onSideListHelperVisibleSetup(view);
			}
		}
	}

	public void updateSideActionMenuItems() {
		if (sideListHelper == null) {
			log(Log.ERROR, TAG, "updateSideActionMenuItems: noSideListHelper. "
					+ AndroidUtils.getCompressedStackTrace());
			return;
		}
		sideListHelper.updateSideActionMenuItems();
	}

	public void updateSideListRefreshButton() {
		if (sideListHelper == null) {
			log(Log.ERROR, TAG, "updateSideListRefreshButton: noSideListHelper. "
					+ AndroidUtils.getCompressedStackTrace());
			return;
		}
		sideListHelper.updateRefreshButton();
	}

	public boolean flipSideListExpandState() {
		if (sideListHelper == null) {
			log(Log.ERROR, TAG, "addSideListEntry: flipSideListExpandState. "
					+ AndroidUtils.getCompressedStackTrace());
			return false;
		}
		return sideListHelper.flipExpandState();
	}

	public void rebuildSideList() {
		setupSideListArea(AndroidUtilsUI.getContentView(this));
	}
}