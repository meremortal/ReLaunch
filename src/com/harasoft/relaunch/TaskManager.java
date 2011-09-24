package com.harasoft.relaunch;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.harasoft.relaunch.ResultsActivity.FLSimpleAdapter;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ScaleDrawable;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Debug.MemoryInfo;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class TaskManager extends Activity {
    final String                  TAG = "TaskManager";

    ReLaunchApp                   app;
    SharedPreferences             prefs;
    PackageManager                pm;
    int                           sortMethod;
    int                           sortCpu;
    int                           sortSize;
    int                           sortAbc;
    EditText                      title;
    Button                        sortSizeBtn;
    Button                        sortCpuBtn;
    Button                        sortAbcBtn;

    final int                     CPUUpdate_i = 5000;
    long                          cpuTotal = 0;
    long                          cpuTotal_prev = 0;
    long                          cpuIdle = 0;
    long                          cpuIdle_prev = 0;
    boolean                       CPUUpdate_active = false;
    Handler                       CPUUpdate_h = new Handler();
    Runnable                      CPUUpdate = new Runnable() {
         public void run() { CPUUpdate_proc(); }
    };
    long                          CPUUsage;
    int                           memTotal;
    long                          memUsage;

    List<Integer>                 taskPids = new ArrayList<Integer>();
    List<Integer>                 servPids = new ArrayList<Integer>();
    ListView                      lv_t;
    TAdapter                      adapter_t;
    ListView                      lv_s;
    SAdapter                      adapter_s;

    // Process info
    static class PInfo {
        PInfo() { prev = 0; curr = 0; usage = 0; mem = 0; }
        long     prev;
        long     curr;
        Integer  usage;
        Integer  mem;
        int      imp;
        String   label;
        String   name;
        String   extra;
        Drawable icon;
    }
    HashMap<Integer, PInfo> pinfo = new HashMap<Integer, PInfo>();

    public class cpuComparator implements java.util.Comparator<Integer>
    {
        public int compare(Integer o1, Integer o2)
        {
            PInfo p1 = pinfo.get(o1);
            PInfo p2 = pinfo.get(o2);
            if (p1 == null)
            {
                if (p2 == null)
                    return 0;
                else
                    return 1;
            }
            else if (p2 == null)
                return -1;
                
            int rc = -p1.usage.compareTo(p2.usage);
            if (rc == 0)
                return -p1.mem.compareTo(p2.mem);
            else
                return rc;
        }
    }
    public class sizeComparator implements java.util.Comparator<Integer>
    {
        public int compare(Integer o1, Integer o2)
        {
            PInfo p1 = pinfo.get(o1);
            PInfo p2 = pinfo.get(o2);
            if (p1 == null)
            {
                if (p2 == null)
                    return 0;
                else
                    return 1;
            }
            else if (p2 == null)
                return -1;
                
            return -p1.mem.compareTo(p2.mem);
        }
    }
    public class abcComparator implements java.util.Comparator<Integer>
    {
        public int compare(Integer o1, Integer o2)
        {
            PInfo p1 = pinfo.get(o1);
            PInfo p2 = pinfo.get(o2);
            if (p1 == null)
            {
                if (p2 == null)
                    return 0;
                else
                    return 1;
            }
            else if (p2 == null)
                return -1;
            
            if (p1.name == null)
            {
                if (p2.name == null)
                    return 0;
                else
                    return 1;
            }
            else if (p2.name == null)
                return -1;
            int rc = -p1.name.compareTo(p2.name);
            if (rc == 0)
                return -p1.mem.compareTo(p2.mem);
            else
                return rc;
        }
    }
    
    private boolean sortLists()
    {
        if (sortMethod == sortCpu)
        {
            Collections.sort(taskPids, new cpuComparator());
            Collections.sort(servPids, new cpuComparator());
            return true;
        }
        else if (sortMethod == sortSize)
        {
            Collections.sort(taskPids, new sizeComparator());
            Collections.sort(servPids, new sizeComparator());
            return true;
        }
        else if (sortMethod == sortAbc)
        {
            Collections.sort(taskPids, new abcComparator());
            Collections.sort(servPids, new abcComparator());
            return true;
        }
        return false;
    }
    private void setSorting(int m)
    {
        sortMethod = m;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("sortMethod", sortMethod);
        editor.commit();

        
        if (m == sortCpu)
        {
            sortCpuBtn.setEnabled(false);
            sortSizeBtn.setEnabled(true);
            sortAbcBtn.setEnabled(true);
            if (sortLists())
            {
                adapter_t.notifyDataSetChanged();
                adapter_s.notifyDataSetChanged();
            }
        }
        else if (m == sortSize)
        {
            sortCpuBtn.setEnabled(true);
            sortSizeBtn.setEnabled(false);
            sortAbcBtn.setEnabled(true);
            if (sortLists())
            {
                adapter_t.notifyDataSetChanged();
                adapter_s.notifyDataSetChanged();
            }
        }
        else if (m == sortAbc)
        {
            sortCpuBtn.setEnabled(true);
            sortSizeBtn.setEnabled(true);
            sortAbcBtn.setEnabled(false);
            if (sortLists())
            {
                adapter_t.notifyDataSetChanged();
                adapter_s.notifyDataSetChanged();
            }
        }
    }
    private void stopCPUUpdate()
    {
        CPUUpdate_active = false;
        CPUUpdate_h.removeCallbacks(CPUUpdate);
    }

    private void startCPUUpdate()
    {
        if (!CPUUpdate_active)
        {
            CPUUpdate_active = true;
            CPUUpdate_proc();
        }
    }

    private void CPUUpdate_proc()
    {
        List<Integer>                 newTask = new ArrayList<Integer>();
        List<Integer>                 newServ = new ArrayList<Integer>();
        HashMap<Integer, PInfo>       newPinfo = new HashMap<Integer, PInfo>();

        /*
         * New tasks list
         */
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> l1 = am.getRunningAppProcesses();
        if (l1 != null)
        {
            int[] pids = new int[l1.size()];
            for (int i=0; i<l1.size(); i++)
                pids[i] = l1.get(i).pid;
            Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(pids);
            for (int i=0; i<l1.size(); i++)
            {
                ActivityManager.RunningAppProcessInfo pi = l1.get(i);
                PInfo p = new PInfo();

                String is = "";
                switch (pi.importance)
                {
                case ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND:
                    p.imp = 2;
                    is = "BACKGROUND";
                    break;
                case ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY:
                    p.imp = 3;
                    is = "NOT ACTIVE";
                    break;
                case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND:
                    p.imp = 1;
                    is = "FOREGROUND";
                    break;
                case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE:
                    p.imp = 2;
                    is = "SERVICE";
                    break;
                case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE:
                    p.imp = 1;
                    is = "VISIBLE";
                    break;
                }
            
                p.name = pi.processName;
                p.extra = is;
                p.mem = mis[i].otherPrivateDirty + mis[i].nativePrivateDirty + mis[i].dalvikSharedDirty;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pi.processName, 0);
                    p.icon = pm.getApplicationIcon(ai.packageName);
                    p.label = pm.getApplicationLabel(ai).toString();
                } catch(PackageManager.NameNotFoundException e) { p.icon = null; p.label = null; }
                newPinfo.put(pids[i], p);
                newTask.add(pids[i]);
            }
        }
        
        /*
         * New services list
         */
        List<ActivityManager.RunningServiceInfo> l2 = am.getRunningServices(1024);
        if(l2 != null){
            int[] pids = new int[l2.size()];
            for(int i=0;i<l2.size();++i)
                pids[i] = l2.get(i).pid;
            Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(pids);

            for(int i=0;i<l2.size();++i)
            {
                ActivityManager.RunningServiceInfo si = l2.get(i);
                PInfo p = new PInfo();

                String fs = "";
                p.imp = 3;
                if ((si.flags & ActivityManager.RunningServiceInfo.FLAG_FOREGROUND) != 0) {
                    p.imp = 1;
                    fs = fs.equals("") ? "FOREGROUND" : (fs + ",FOREGROUND");
                }
                if ((si.flags & ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS) != 0) {
                    p.imp = 1;
                    fs = fs.equals("") ? "PERSISTENT" : (fs + ",PERSISTENT");
                }
                if ((si.flags & ActivityManager.RunningServiceInfo.FLAG_STARTED) != 0) {
                    p.imp = 2;
                    fs = fs.equals("") ? "STARTED" : (fs + ",STARTED");
                }
                if ((si.flags & ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS) != 0) {
                    p.imp = 1;
                    fs = fs.equals("") ? "SYSTEM" : (fs + ",SYSTEM");
                }

                p.name = si.clientPackage;
                p.extra = fs;
                p.mem = mis[i].otherPrivateDirty + mis[i].nativePrivateDirty + mis[i].dalvikSharedDirty;
                newPinfo.put(pids[i], p);
                newServ.add(pids[i]);
            }
        }

        /*
         * Merge new pinfo list with the old one
         */
        for (Integer k : pinfo.keySet())
            if (!newPinfo.containsKey(k))
                pinfo.remove(k);
        for (Integer k : pinfo.keySet())
        {
            PInfo n = newPinfo.get(k);
            pinfo.get(k).mem = n.mem;
        }
        for (Integer k : newPinfo.keySet())
            if (!pinfo.containsKey(k))
                pinfo.put(k, newPinfo.get(k));

        /*
         *  Update CPU usage
         *  ----------------
         */
        getCPU();
        for (Integer k : pinfo.keySet())
            pinfo.get(k).curr = getCPU(k);
 
        if (cpuTotal_prev != 0  &&  cpuIdle_prev != 0)
        {
            // CPU total
            long deltaTotal =  cpuTotal - cpuTotal_prev;
            long deltaIdle = cpuIdle - cpuIdle_prev;
            CPUUsage = (deltaTotal != 0) ? (100 * (deltaTotal - deltaIdle)) / deltaTotal : 0;

            // Per process
            for (Integer k : pinfo.keySet())
            {
                PInfo p = pinfo.get(k);
                if (p.prev != 0)
                    p.usage = (int)((deltaTotal != 0) ? (100 * (p.curr - p.prev)) / deltaTotal : 0);
                pinfo.put(k, p);
            }
        }
        
        // Update memory usage
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memoryInfo);
        memUsage = memoryInfo.availMem;

        // Save current values as previous
        for (Integer k : pinfo.keySet())
            pinfo.get(k).prev = pinfo.get(k).curr;
        cpuTotal_prev = cpuTotal;
        cpuIdle_prev = cpuIdle;
        
        taskPids = newTask;
        servPids = newServ;
        sortLists();
        adapter_t.notifyDataSetChanged();
        adapter_s.notifyDataSetChanged();

        // Set title
        title.setText(Html.fromHtml("Memory: Total <b>" + memTotal/1024 + "</b>M / Used: <b>" + memUsage/1048576 + "</b>M\n"
                + "CPU usage: <b>" + CPUUsage + "</b>%"), TextView.BufferType.SPANNABLE);

        //// DEBUG+++++
        //Log.d(TAG, "---------------");
        //Log.d(TAG, "CPU usage: " + CPUUsage + "%");
        //Log.d(TAG, "--- Tasks:");
        //for (Integer pid : taskPids)
        //{
        //    PInfo pi = pinfo.get(pid);
        //    Log.d(TAG, pi.name + " " + pi.extra + " CPU:" + pi.usage + "% MEM:" + pi.mem + "K");
        //}
        //Log.d(TAG, "--- Services:");
        //for (Integer pid : taskPids)
        //{
        //    PInfo pi = pinfo.get(pid);
        //    Log.d(TAG, pi.name + " " + pi.extra + " CPU:" + pi.usage + "% MEM:" + pi.mem + "K");
        //}
        //// DEBUG-----

        /*
         *  Next CPUUpdate_proc call
         */
        if (CPUUpdate_active)
            CPUUpdate_h.postDelayed(CPUUpdate, CPUUpdate_i);
    }
    
    private void getCPU()
    {
        try
        {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")), 1000);
            String load = reader.readLine();
            reader.close();     

            String[] toks = load.split("\\s+");

            cpuIdle =  Long.parseLong(toks[4]) + Long.parseLong(toks[5]);
            cpuTotal = Long.parseLong(toks[1]) + Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + cpuIdle
                + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
        }
        catch(IOException ex) { }
    }
    private long getCPU(int pid)
    {
        try
        {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/" + pid + "/stat")), 1000);
            String load = reader.readLine();
            reader.close();     

            String[] toks = load.split("\\s+");
            return Long.parseLong(toks[13]) + Long.parseLong(toks[14]);
        }
        catch(IOException ex) { return 0; }  
    }

    static class ViewHolder {
        EditText  tv1;
        EditText  tv2;
        EditText  tv3;
        ImageView iv;
    }
    class TAdapter extends ArrayAdapter<Integer> {
        TAdapter(Context context, int resource)
        {
            super(context, resource);
        }

        @Override
        public int getCount() {
            return taskPids.size();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            View       v = convertView;
            if (v == null) {
                LayoutInflater vi = (LayoutInflater)getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                v = vi.inflate(R.layout.taskmanager_item, null);
                holder = new ViewHolder();
                holder.tv1 = (EditText) v.findViewById(R.id.tm_label);
                holder.tv2 = (EditText) v.findViewById(R.id.tm_fullname);
                holder.tv3 = (EditText) v.findViewById(R.id.tm_extra);
                holder.iv  = (ImageView) v.findViewById(R.id.tm_icon);
                v.setTag(holder);
            }
            else
                holder = (ViewHolder) v.getTag();

            TextView  tv1 = holder.tv1;
            TextView  tv2 = holder.tv2;
            TextView  tv3 = holder.tv3;
            ImageView iv = holder.iv;
            Integer item = taskPids.get(position);
            PInfo   pi = pinfo.get(item);
            if (item != null) {
                String label = (pi.label == null) ? "???" : pi.label;
                switch (pi.imp)
                {
                case 1:
                    SpannableString s = new SpannableString(label);
                    s.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), 0);
                    tv1.setBackgroundColor(getResources().getColor(R.color.foreground_task_bg));
                    tv1.setTextColor(getResources().getColor(R.color.foreground_task_fg));
                    tv1.setText(s);
                    break;
                case 2:
                    tv1.setBackgroundColor(getResources().getColor(R.color.background_task_bg));
                    tv1.setTextColor(getResources().getColor(R.color.backgorund_task_fg));
                    tv1.setText(label);
                    break;
                case 3:
                    tv1.setBackgroundColor(getResources().getColor(R.color.nonactive_task_bg));
                    tv1.setTextColor(getResources().getColor(R.color.nonactive_task_fg));
                    tv1.setText(label);
                    break;
                }
                tv2.setText(pi.name);
                if (pi.extra.equals(""))
                    tv3.setText(Html.fromHtml("CPU: <b>" + pi.usage + "</b>%, Mem: <b>" + pi.mem + "</b>K"), TextView.BufferType.SPANNABLE);
                else
                    tv3.setText(Html.fromHtml(pi.extra + ", CPU: <b>" + pi.usage + "</b>%, Mem: <b>" + pi.mem + "</b>K"), TextView.BufferType.SPANNABLE);
                if (pi.icon == null)
                    iv.setImageDrawable(getResources().getDrawable(R.drawable.file_ok));
                else 
                    iv.setImageDrawable(pi.icon);
            }
            return v;
        }
    }

    class SAdapter extends ArrayAdapter<Integer> {
        SAdapter(Context context, int resource)
        {
            super(context, resource);
        }

        @Override
        public int getCount() {
            return servPids.size();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            View       v = convertView;
            if (v == null) {
                LayoutInflater vi = (LayoutInflater)getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                v = vi.inflate(R.layout.taskmanager_item, null);
                holder = new ViewHolder();
                holder.tv1 = (EditText) v.findViewById(R.id.tm_label);
                holder.tv2 = (EditText) v.findViewById(R.id.tm_fullname);
                holder.tv3 = (EditText) v.findViewById(R.id.tm_extra);
                holder.iv  = (ImageView) v.findViewById(R.id.tm_icon);
                v.setTag(holder);
            }
            else
                holder = (ViewHolder) v.getTag();

            TextView  tv1 = holder.tv1;
            TextView  tv2 = holder.tv2;
            TextView  tv3 = holder.tv3;
            ImageView iv = holder.iv;

            Integer item = servPids.get(position);
            PInfo   pi = pinfo.get(item);
            if (item != null) {
                tv1.setText(pi.name);
                tv3.setText(Html.fromHtml(pi.extra + ", CPU: <b>" + pi.usage + "%</b>, Mem: <b>" + pi.mem + "K</b>"), TextView.BufferType.SPANNABLE);
            }
            return v;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        app = ((ReLaunchApp)getApplicationContext());
        app.setFullScreenIfNecessary(this);
        pm = getPackageManager();
        setContentView(R.layout.taskmanager_layout);

        sortCpu = getResources().getInteger(R.integer.SORT_CPU);
        sortSize = getResources().getInteger(R.integer.SORT_SIZE);
        sortAbc = getResources().getInteger(R.integer.SORT_ABC);
        sortMethod = prefs.getInt("sortMethod", sortCpu);
        lv_t = (ListView) findViewById(R.id.tasks_lv);
        adapter_t = new TAdapter(this, R.layout.taskmanager_item);
        lv_t.setAdapter(adapter_t);
        lv_s = (ListView) findViewById(R.id.services_lv);
        adapter_s = new SAdapter(this, R.layout.taskmanager_item);
        lv_s.setAdapter(adapter_s);

        // Get total memory
        try
        {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")), 1000);
            String load = reader.readLine();
            reader.close();     

            String[] toks = load.split("\\s+");
            memTotal = Integer.parseInt(toks[1]);
        }
        catch(IOException ex) { memTotal = 0; }

        title = (EditText) findViewById(R.id.title_txt);
        ((ImageButton)findViewById(R.id.tm_back)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }});
        sortSizeBtn =  (Button)findViewById(R.id.sort_size);
        sortSizeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSorting(sortSize); }});
        sortCpuBtn =  (Button)findViewById(R.id.sort_cpu);
        sortCpuBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSorting(sortCpu); }});
        sortAbcBtn =  (Button)findViewById(R.id.sort_abc);
        sortAbcBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSorting(sortAbc); }});
        setSorting(sortMethod);
        startCPUUpdate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCPUUpdate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCPUUpdate();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        startCPUUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCPUUpdate();
   }

    @Override
    protected void onStart() {
        super.onStart();
        startCPUUpdate();
  }

    @Override
    protected void onStop() {
        super.onStop();
        stopCPUUpdate();
 }
    
    
}