package com.horizon.exchangeapi;

// Creation and use of this file is described at https://stackoverflow.com/questions/17897171/how-to-have-a-patch-annotation-for-jax-rs
import javax.ws.rs.HttpMethod;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod("PATCH")
public @interface PATCH { }
