package com.holi.prdbus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

/** Always-readable privacy explanation, independent of the one-time notices. */
public final class PrivacyActivity extends ThemedActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.edgeToEdge(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setBackgroundColor(Ui.BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setBackgroundColor(Ui.SURFACE);
        Button back=Ui.button(this,"",Ui.SURFACE,Ui.PRIMARY);Ui.icon(back,"back",Ui.PRIMARY);
        back.setContentDescription("返回");back.setOnClickListener(v->finish());
        header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        TextView title=new TextView(this);title.setText("关于隐私");title.setTextSize(22);title.setTextColor(Ui.TEXT);header.addView(title);
        page.addView(header,Ui.matchWrap());
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(1);
        content.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,24));scroll.addView(content);
        section(content,"本机记录与网络查询",PrivacyNotice.GENERAL);
        section(content,"高德 Key 与数据使用",PrivacyNotice.AMAP);
        Button policy=Ui.button(this,"查看高德隐私政策 ↗",Ui.RAISED,Ui.PRIMARY);
        policy.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://lbs.amap.com/pages/privacy/")));}catch(android.content.ActivityNotFoundException ignored){}});
        content.addView(policy,Ui.cardParams(this));
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);Ui.insets(this,header,8,16,4,6);
    }
    private void section(LinearLayout content,String title,String text) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16));
        box.setBackground(Ui.rounded(this,Ui.SURFACE,18));TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(18);heading.setTextColor(Ui.TEXT);
        box.addView(heading);TextView body=new TextView(this);body.setText(text);body.setTextSize(15);body.setTextColor(Ui.MUTED);body.setLineSpacing(Ui.dp(this,3),1.08f);
        body.setPadding(0,Ui.dp(this,12),0,0);box.addView(body);content.addView(box,Ui.cardParams(this));
    }
}
