package net.kvak.shibboleth.totpauth.authn.impl;

import java.util.List;

import javax.annotation.Nonnull;
import javax.naming.directory.Attribute;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.servlet.http.HttpServletRequest;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import org.apache.commons.lang.StringUtils;
import org.opensaml.profile.action.ActionSupport;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.warrenstrange.googleauth.GoogleAuthenticator;

import net.kvak.shibboleth.totpauth.api.authn.context.TokenUserContext;
import net.kvak.shibboleth.totpauth.api.authn.context.TokenUserContext.AuthState;
import net.kvak.shibboleth.totpauth.authn.impl.TotpUtils;
import net.shibboleth.idp.authn.AuthnEventIds;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.UsernamePasswordContext;
import net.shibboleth.idp.profile.AbstractProfileAction;
import net.shibboleth.utilities.java.support.annotation.constraint.NotEmpty;
import net.shibboleth.utilities.java.support.component.ComponentSupport;
import net.shibboleth.utilities.java.support.primitive.StringSupport;

/**
 * Validates users TOTP token code against injected authenticator
 *
 * An action that checks for a {@link TokenCodeContext} and directly produces an
 * {@link net.shibboleth.idp.authn.AuthenticationResult} based on submitted
 * tokencode and username
 *
 * @author korteke
 *
 */

/*
 * TODO, EVERYTHING..
 */
@SuppressWarnings({ "rawtypes", "deprecation" })
public class RegisterNewSeedSql extends AbstractProfileAction {

	/** Class logger. */
	@Nonnull
	@NotEmpty
	private final Logger log = LoggerFactory.getLogger(RegisterNewToken.class);

	/** Google Authenticator **/
	@Nonnull
	@NotEmpty
	private GoogleAuthenticator gAuth;

	/** JdbcTemplate **/
	private JdbcTemplate jdbcTemplate;

	/** TokenCodeField that is on RegisterToken form **/
	@Nonnull
	@NotEmpty
	private String tokenCodeField;

	/** Name of the table in the seed db **/
	@Nonnull
	@NotEmpty
	private String seedDbTableName;

	/** Name of the username column in the seed db **/
	@Nonnull
	@NotEmpty
	private String usernameColumnName;

	/** Name of the seed column in the seed db **/
	@Nonnull
	@NotEmpty
	private String seedColumnName;

	/** Username context for username **/
	@Nonnull
	@NotEmpty
	private UsernamePasswordContext upCtx;

	/** Token user context */
	@Nonnull
	@NotEmpty
	private TokenUserContext tokenCtx;

	/** Inject seedDataSource */
	public void setSeedDataSource(DataSource seedDataSource) {
		this.jdbcTemplate = new JdbcTemplate(seedDataSource);
	}

	/** Inject token authenticator */
	public void setgAuth(@Nonnull @NotEmpty final GoogleAuthenticator gAuth) {
		this.gAuth = gAuth;
	}

	/**
	 * Constructor Initialize user and seed attributes
	 */
	public RegisterNewSeedSql() {
	}

	public void setSeedDBTableName(@Nonnull @NotEmpty final String fieldName) {
		log.debug("{} {} is the seed table name", getLogPrefix(), fieldName);
		ComponentSupport.ifInitializedThrowUnmodifiabledComponentException(this);
		seedDbTableName = fieldName;
	}

	public void setUsernameColumnName(@Nonnull @NotEmpty final String fieldName) {
		log.debug("{} {} is the username column name", getLogPrefix(), fieldName);
		ComponentSupport.ifInitializedThrowUnmodifiabledComponentException(this);
		usernameColumnName = fieldName;
	}

	public void setSeedColumnName(@Nonnull @NotEmpty final String fieldName) {
		log.debug("{} {} is the seed column name", getLogPrefix(), fieldName);
		ComponentSupport.ifInitializedThrowUnmodifiabledComponentException(this);
		seedColumnName = fieldName;
	}

	public void settokenCodeField(@Nonnull @NotEmpty final String fieldName) {
		log.debug("{} {} is tokencode field from the form", getLogPrefix(), fieldName);
		ComponentSupport.ifInitializedThrowUnmodifiabledComponentException(this);
		tokenCodeField = fieldName;
	}

	@Override
	protected boolean doPreExecute(ProfileRequestContext profileRequestContext) {
		log.debug("Entering GenerateNewToken doPreExecute");

		try {
			tokenCtx = profileRequestContext.getSubcontext(AuthenticationContext.class)
					.getSubcontext(TokenUserContext.class, true);
			upCtx = profileRequestContext.getSubcontext(AuthenticationContext.class)
					.getSubcontext(UsernamePasswordContext.class);
			return true;
		} catch (Exception e) {
			log.debug("Error with doPreExecute", e);
			return false;

		}

	}

	protected void doExecute(@Nonnull final ProfileRequestContext profileRequestContext) {
		log.debug("{} Entering RegisterNewSeedSql", getLogPrefix());

		final HttpServletRequest request = getHttpServletRequest();
		final TotpUtils totpUtils = new TotpUtils();

		if (request == null) {
			log.debug("{} Empty request", getLogPrefix());
			ActionSupport.buildEvent(profileRequestContext, AuthnEventIds.NO_CREDENTIALS);
			return;
		}


		String username = upCtx.getUsername();
		String token = StringSupport.trimOrNull(request.getParameter(tokenCodeField));

		if (!StringUtils.isNumeric(token) || Strings.isNullOrEmpty(token)) {
			log.debug("{} Empty or invalid tokenCode", getLogPrefix());
			tokenCtx.setState(AuthState.CANT_VALIDATE);

			ActionSupport.buildEvent(profileRequestContext, AuthnEventIds.INVALID_CREDENTIALS);
			return;

		} else {
			boolean tokenValidate = totpUtils.validateToken(tokenCtx.getSharedSecret(), Integer.parseInt(token));
			if (tokenValidate) {
				boolean result = registerSeed(username, tokenCtx.getSharedSecret());
				if (!result) {
					ActionSupport.buildEvent(profileRequestContext, AuthnEventIds.ACCOUNT_ERROR);
				}
			} else {
				log.debug("Invalid token. Returning.");
				tokenCtx.setState(AuthState.CANT_VALIDATE);
				ActionSupport.buildEvent(profileRequestContext, AuthnEventIds.INVALID_CREDENTIALS);
			}
		}
	}

	private boolean registerSeed(String username, String sharedSecret) {

		/** Make sure there isn't already a seed for this user **/

		String existingSeed = this.jdbcTemplate.queryForObject(
        	"select ? from ? where ? = ?",
        	new Object[]{usernameColumnName, seedDbTableName, usernameColumnName, username},
        	String.class);

		if (!Strings.isNullOrEmpty(existingSeed)) {
			log.debug("{} found existing seed. Aborting register new seed", getLogPrefix());
			return false;
		}

		try {
			this.jdbcTemplate.update(
        		"insert into ? (?, ?) values (?, ?)",
        		seedDbTableName, usernameColumnName, seedColumnName,
        		username, sharedSecret);
			return true;
		} catch (Exception e) {
			log.debug("{} registerSeer error", getLogPrefix(), e);
			return false;
		}

	}
}
