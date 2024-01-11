package com.alibaba.android.arouter.demo.module1.testservice;

import com.alibaba.android.arouter.facade.template.IProvider;

public abstract class AbsService implements IProvider {

    public abstract void sayHi(String name);
}
