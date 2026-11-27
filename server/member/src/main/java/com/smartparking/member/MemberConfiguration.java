package com.smartparking.member;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.control.devices.DeviceViews;
import com.smartparking.member.invite.InviteService;
import com.smartparking.member.members.MemberService;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 被授权人与邀请（BIZ-11、BIZ-12） */
@Configuration(proxyBeanMethods = false)
public class MemberConfiguration {

    @Bean
    MemberService memberService(JdbcClient jdbc, SystemSettings settings, ApplicationEventPublisher events) {
        return new MemberService(jdbc, settings, events, Clock.systemUTC());
    }

    @Bean
    InviteService inviteService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            SystemSettings settings,
            DeviceViews views,
            ApplicationEventPublisher events) {
        return new InviteService(jdbc, tx, settings, views, events, Clock.systemUTC());
    }
}
