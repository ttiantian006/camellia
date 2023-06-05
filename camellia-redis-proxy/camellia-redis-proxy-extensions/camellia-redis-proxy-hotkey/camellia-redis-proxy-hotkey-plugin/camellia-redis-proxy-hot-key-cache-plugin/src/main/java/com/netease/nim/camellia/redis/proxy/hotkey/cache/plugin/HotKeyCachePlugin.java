package com.netease.nim.camellia.redis.proxy.hotkey.cache.plugin;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.command.CommandContext;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.hotkey.common.ProxyHotKeyServerDiscovery;
import com.netease.nim.camellia.redis.proxy.hotkey.common.ProxyLocalHotKeyServerDiscovery;
import com.netease.nim.camellia.redis.proxy.hotkey.common.Utils;
import com.netease.nim.camellia.redis.proxy.plugin.*;
import com.netease.nim.camellia.redis.proxy.plugin.hotkeycache.HotValue;
import com.netease.nim.camellia.redis.proxy.reply.BulkReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.util.BeanInitUtils;

public class HotKeyCachePlugin implements ProxyPlugin {
    public static final String HOT_KEY_CACHE_PLUGIN_ALIAS = "hotKeyCachePlugin";
    private HotKeyCacheManager hotKeyCacheManager;

    @Override
    public void init(ProxyBeanFactory factory) {
        // 默认使用本地sever
        String hotKeyCacheDiscoveryClassName = ProxyDynamicConf.getString("hot.key.server.discovery.className", ProxyLocalHotKeyServerDiscovery.class.getName());
        ProxyHotKeyServerDiscovery discovery = (ProxyHotKeyServerDiscovery) factory.getBean(BeanInitUtils.parseClass(hotKeyCacheDiscoveryClassName));
        HotKeyCacheConfig hotKeyCacheConfig = new HotKeyCacheConfig();
        hotKeyCacheConfig.setDiscovery(discovery.getDiscovery());
        hotKeyCacheManager = new HotKeyCacheManager(hotKeyCacheConfig);
    }

    @Override
    public ProxyPluginOrder order() {
        return new ProxyPluginOrder() {
            @Override
            public int request() {
                return Utils.getRequestOrder(HOT_KEY_CACHE_PLUGIN_ALIAS, 10000);
            }

            @Override
            public int reply() {
                return Utils.getReplyOrder(HOT_KEY_CACHE_PLUGIN_ALIAS, Integer.MIN_VALUE + 10000);
            }
        };
    }

    @Override
    public ProxyPluginResponse executeRequest(ProxyRequest proxyRequest) {
        Command command = proxyRequest.getCommand();
        RedisCommand redisCommand = command.getRedisCommand();
        // 只对get命令做缓存
        if (redisCommand == RedisCommand.GET) {
            byte[][] objects = command.getObjects();
            if (objects.length > 1) {
                CommandContext commandContext = command.getCommandContext();
                HotKeyCache hotKeyCache = hotKeyCacheManager.getHotKeyCache(commandContext.getBid(), commandContext.getBgroup());
                byte[] key = objects[1];
                HotValue value = hotKeyCache.getCache(key);
                if (value != null) {

                    BulkReply bulkReply = new BulkReply(value.getValue());
                    return new ProxyPluginResponse(false, bulkReply);
                }
            }
            // 如果是del 和 set 命令，需要对cache进行去除
        } else if (redisCommand == RedisCommand.DEL || redisCommand == RedisCommand.SET) {
            tryDeleteCache(command);
        }
        return ProxyPluginResponse.SUCCESS;
    }

    private void tryDeleteCache(Command command) {
        byte[][] objects = command.getObjects();
        if (objects.length > 1) {
            CommandContext commandContext = command.getCommandContext();
            HotKeyCache hotKeyCache = hotKeyCacheManager.getHotKeyCache(commandContext.getBid(), commandContext.getBgroup());
            byte[] key = objects[1];
            if (hotKeyCache.check(key)) {
                // 删除key
                hotKeyCache.delCache(key);
            }
        }
    }

    @Override
    public ProxyPluginResponse executeReply(ProxyReply proxyReply) {
        if (proxyReply.isFromPlugin()) return ProxyPluginResponse.SUCCESS;
        Command command = proxyReply.getCommand();
        if (command == null) return ProxyPluginResponse.SUCCESS;
        RedisCommand redisCommand = command.getRedisCommand();
        if (redisCommand == RedisCommand.GET) {
            Reply reply = proxyReply.getReply();
            if (reply instanceof BulkReply) {
                CommandContext commandContext = proxyReply.getCommandContext();
                HotKeyCache hotKeyCache = hotKeyCacheManager.getHotKeyCache(commandContext.getBid(), commandContext.getBgroup());
                byte[] key = command.getObjects()[1];
                byte[] value = ((BulkReply) reply).getRaw();
                hotKeyCache.tryBuildHotKeyCache(key, value);
            }
        }
        return ProxyPluginResponse.SUCCESS;
    }
}
