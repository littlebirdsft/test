package com.haozileung.infra.cache;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缓存助手
 */
public class RedisCacheManager {

	private final static Logger logger = LoggerFactory.getLogger(RedisCacheManager.class);
	private static CacheProvider provider;

	public static void destroy() {
		if (provider != null) {
			provider.stop();
			provider = null;
		}
		logger.info("RedisCacheManager stopped...");
	}

	private final static Cache _GetCache(String cache_name) {
		if (provider == null) {
			provider = new RedisCacheProvider();
			provider.start();
			logger.info("RedisCacheManager started...");
		}
		return provider.buildCache(cache_name);
	}

	/**
	 * 获取缓存中的数据
	 *
	 * @param name
	 * @param key
	 * @return
	 */
	public final static Object get(String name, Object key) {
		if (name != null && key != null)
			return _GetCache(name).get(key);
		return null;
	}

	/**
	 * 获取缓存中的数据
	 *
	 * @param <T>
	 * @param resultClass
	 * @param name
	 * @param key
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final static <T> T get(Class<T> resultClass, String name, Object key) {
		if (name != null && key != null)
			return (T) _GetCache(name).get(key);
		return null;
	}

	/**
	 * 写入缓存
	 *
	 * @param name
	 * @param key
	 * @param value
	 */
	public final static void set(String name, Object key, Serializable value) {
		if (name != null && key != null && value != null)
			_GetCache(name).put(key, value);
	}

	/**
	 * 清除缓冲中的某个数据
	 *
	 * @param name
	 * @param key
	 */
	public final static void evict(String name, Object key) {
		if (name != null && key != null)
			_GetCache(name).remove(key);
	}

}
