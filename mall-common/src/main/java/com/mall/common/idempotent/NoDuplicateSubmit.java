package com.mall.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NoDuplicateSubmit {

    int lockSeconds() default 5;

    boolean unlockOnFinish() default false;

    String message() default "请勿重复提交，请稍后再试";
}
