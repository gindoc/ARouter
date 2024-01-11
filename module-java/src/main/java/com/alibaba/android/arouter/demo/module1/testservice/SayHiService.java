package com.alibaba.android.arouter.demo.module1.testservice;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.alibaba.android.arouter.demo.service.HelloService;
import com.alibaba.android.arouter.facade.annotation.Route;

@Route(path = "/yourservicegroupname/hi")
public class SayHiService extends AbsService{
    Context mContext;

    @Override
    public void init(Context context) {
        mContext = context;
        Log.e("testService", HelloService.class.getName() + " has init.");
    }

    @Override
    public void sayHi(String name) {
        Toast.makeText(mContext, "Hi " + name, Toast.LENGTH_SHORT).show();

    }
}
