
package com.mattprecious.notisync.wizardpager.ui;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.mattprecious.notisync.R;
import com.mattprecious.notisync.util.Preferences;
import com.mattprecious.notisync.wizardpager.model.Page;

import org.holoeverywhere.LayoutInflater;
import org.holoeverywhere.app.Activity;
import org.holoeverywhere.app.Fragment;
import org.holoeverywhere.widget.CheckBox;

public class ModeFragment extends Fragment implements OnClickListener {
    private static final String ARG_KEY = "key";

    private PageFragmentCallbacks mCallbacks;
    private String mKey;
    private Page mPage;

    private View primaryView;
    private View secondaryView;
    private CheckBox primaryCheckBox;
    private CheckBox secondaryCheckBox;

    public static ModeFragment create(String key) {
        Bundle args = new Bundle();
        args.putString(ARG_KEY, key);

        ModeFragment fragment = new ModeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mKey = args.getString(ARG_KEY);
        mPage = mCallbacks.onGetPage(mKey);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wizard_mode, container, false);

        primaryView = rootView.findViewById(R.id.primary);
        primaryView.setOnClickListener(this);
        
        primaryCheckBox = (CheckBox) rootView.findViewById(R.id.primary_checkbox);
        primaryCheckBox.setClickable(false);

        secondaryView = rootView.findViewById(R.id.secondary);
        secondaryView.setOnClickListener(this);
        
        secondaryCheckBox = (CheckBox) rootView.findViewById(R.id.secondary_checkbox);
        secondaryCheckBox.setClickable(false);

        updateSelection();

        return rootView;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (!(activity instanceof PageFragmentCallbacks)) {
            throw new ClassCastException("Activity must implement PageFragmentCallbacks");
        }

        mCallbacks = (PageFragmentCallbacks) activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = null;
    }

    private void updateSelection() {
        boolean isPrimary = Preferences.isPrimary(getActivity());
        primaryCheckBox.setChecked(isPrimary);
        secondaryCheckBox.setChecked(!isPrimary);
    }

    @Override
    public void onClick(View v) {
        if (v == primaryView) {
            Preferences.setMode(getActivity(), Preferences.Mode.PRIMARY);
        } else {
            Preferences.setMode(getActivity(), Preferences.Mode.SECONDARY);
        }

        updateSelection();
        mPage.notifyDataChanged();
    }

}