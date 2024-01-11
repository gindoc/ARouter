package com.alibaba.android.arouter.demo.module1.testservice;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.alibaba.android.arouter.demo.service.HelloService;
import com.alibaba.android.arouter.facade.annotation.Route;

@Route(path = "/yourservicegroupname/hello2")
public class HelloServiceImpl2 implements HelloService {
    Context mContext;

    @Override
    public void sayHello(String name) {
        Toast.makeText(mContext, "Hello2 " + name, Toast.LENGTH_SHORT).show();
    }

    /**
     * Do your init work in this method, it well be call when processor has been load.
     *
     * @param context ctx
     */
    @Override
    public void init(Context context) {
        mContext = context;
        Log.e("testService", HelloService.class.getName() + " has init.");
    }
}
