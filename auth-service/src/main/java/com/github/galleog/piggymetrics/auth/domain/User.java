package com.github.galleog.piggymetrics.auth.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.Version;
import java.io.Serializable;
import java.util.List;

/**
 * Entity for users.
 */
@Entity
@Table(name = User.TABLE_NAME)
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@JsonDeserialize(builder = User.UserBuilder.class)
@EqualsAndHashCode(of = "username")
public class User implements UserDetails, Serializable {
    @VisibleForTesting
    public static final String TABLE_NAME = "users";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * Unique name of the user used as his/her login name.
     */
    @Id
    @Access(AccessType.PROPERTY)
    private String username;
    /**
     * Password of the user.
     */
    private String password;

    @Version
    @SuppressWarnings("unused")
    private Integer version;

    @Builder
    @SuppressWarnings("unused")
    private User(@NonNull String username, @NonNull String password) {
        setUsername(username);
        setPassword(password);
    }

    @NonNull
    @Override
    public String getUsername() {
        return this.username;
    }

    private void setUsername(String username) {
        Validate.notBlank(username);
        this.username = username;
    }

    @NonNull
    @Override
    public String getPassword() {
        return password;
    }

    private void setPassword(String password) {
        Validate.notBlank(password);
        this.password = ENCODER.encode(password);
    }

    @Override
    @NonNull
    @Transient
    @JsonIgnore
    public List<GrantedAuthority> getAuthorities() {
        return ImmutableList.of();
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append(getUsername()).build();
    }

    @JsonPOJOBuilder(withPrefix = StringUtils.EMPTY)
    public static final class UserBuilder {
    }
}
