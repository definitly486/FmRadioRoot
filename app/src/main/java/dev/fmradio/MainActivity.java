package dev.fmradio;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class MainActivity extends Activity implements RadioService.Ui {
    private RadioService svc;
    private AudioManager audio;

    private TextView tvStatus, tvFreq, tvPs, tvRt, tvLog;
    private ScrollView logScroll;
    private SeekBar dial, volume;
    private Button btnPower, btnScan, btnFav;
    private CheckBox chkSpeaker;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<Integer> listFreqs = new ArrayList<>();

    private boolean dragging;
    private boolean updatingUi;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((RadioService.LocalBinder) binder).get();
            svc.setUi(MainActivity.this);
            StringBuilder sb = new StringBuilder();
            for (String l : svc.getLog()) sb.append(l).append('\n');
            tvLog.setText(sb);
            scrollLog();
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        tvStatus = findViewById(R.id.status);
        tvFreq = findViewById(R.id.freq);
        tvPs = findViewById(R.id.ps);
        tvRt = findViewById(R.id.rt);
        tvLog = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        dial = findViewById(R.id.dial);
        volume = findViewById(R.id.volume);
        btnPower = findViewById(R.id.btnPower);
        btnScan = findViewById(R.id.btnScan);
        btnFav = findViewById(R.id.btnFav);
        chkSpeaker = findViewById(R.id.chkSpeaker);
        list = findViewById(R.id.stations);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);

        btnPower.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc == null) return;
                if (svc.isPowered() || svc.isBusy()) {
                    svc.powerOff();
                } else {
                    startPower(false);
                }
            }
        });
        findViewById(R.id.btnDown).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc != null) svc.step(-100);
            }
        });
        findViewById(R.id.btnUp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc != null) svc.step(100);
            }
        });
        findViewById(R.id.btnSeekPrev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc != null) svc.seek(false);
            }
        });
        findViewById(R.id.btnSeekNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc != null) svc.seek(true);
            }
        });
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc == null) return;
                if (svc.isScanning()) {
                    svc.stopScan();
                } else if (svc.isPowered()) {
                    svc.startScan();
                } else {
                    startPower(true);
                }
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc != null) svc.toggleFavorite(svc.getFreq());
            }
        });
        chkSpeaker.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                if (!updatingUi && svc != null) svc.setSpeaker(checked);
            }
        });

        dial.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    tvFreq.setText(Stations.format(RadioService.MIN_KHZ + p * 100));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.tune(RadioService.MIN_KHZ + s.getProgress() * 100);
            }
        });

        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (svc == null || pos >= listFreqs.size()) return;
                svc.tune(listFreqs.get(pos));
                if (!svc.isPowered() && !svc.isBusy()) startPower(false);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                if (svc == null || pos >= listFreqs.size()) return false;
                svc.toggleFavorite(listFreqs.get(pos));
                return true;
            }
        });

        tvLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("fm-log", tvLog.getText()));
                Toast.makeText(MainActivity.this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        volume.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        render();
    }

    @Override
    protected void onStop() {
        if (svc != null) {
            svc.setUi(null);
            svc.stopIfIdle();
            svc = null;
        }
        unbindService(conn);
        super.onStop();
    }

    private void startPower(boolean scan) {
        Intent i = new Intent(this, RadioService.class)
                .setAction(RadioService.ACTION_POWER_ON)
                .putExtra(RadioService.EXTRA_SCAN, scan);
        startForegroundService(i);
    }

    // ---------------------------------------------------------- Ui events

    @Override
    public void onChanged() {
        render();
    }

    @Override
    public void onLog(String line) {
        tvLog.append(line + "\n");
        scrollLog();
    }

    private void scrollLog() {
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void render() {
        if (svc == null) return;
        updatingUi = true;
        try {
            int f = svc.getFreq();
            tvFreq.setText(Stations.format(f) + " МГц");
            if (!dragging) dial.setProgress((f - RadioService.MIN_KHZ) / 100);
            tvPs.setText(svc.getPs());
            tvRt.setText(svc.getRt());

            boolean on = svc.isPowered() || svc.isBusy();
            btnPower.setText(on ? R.string.btn_power_off : R.string.btn_power_on);
            btnScan.setText(svc.isScanning() ? R.string.scanning : R.string.scan);
            btnFav.setText(svc.isFavorite(f) ? R.string.fav_remove : R.string.fav_add);
            chkSpeaker.setChecked(svc.isSpeaker());

            String s = svc.getStatus();
            if (!headsetPlugged()) {
                s += "\nПодключите проводные наушники — они служат антенной";
            }
            tvStatus.setText(s);

            TreeSet<Integer> all = new TreeSet<>(svc.getFound());
            all.addAll(svc.getFavs());
            listFreqs.clear();
            listFreqs.addAll(all);
            adapter.clear();
            for (int khz : listFreqs) {
                String t = Stations.format(khz) + " МГц";
                if (svc.isFavorite(khz)) t += "   ★";
                if (khz == f && svc.isPowered()) t = "▶ " + t;
                adapter.add(t);
            }
            adapter.notifyDataSetChanged();
        } finally {
            updatingUi = false;
        }
    }

    private boolean headsetPlugged() {
        AudioDeviceInfo[] devs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devs) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }
}
