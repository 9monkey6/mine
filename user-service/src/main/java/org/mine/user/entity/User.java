package org.mine.user.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;  // 用户名
    private String password;  // 密码
    private String phone;     // 扩展：手机号
    private String email;     // 扩展：邮箱
    private Integer status;   // 扩展：状态 0禁用 1正常
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}