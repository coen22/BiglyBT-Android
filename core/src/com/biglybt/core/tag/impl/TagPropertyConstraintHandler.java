/*
 * Created on Sep 4, 2013
 * Created by Paul Gardner
 *
 * Copyright 2013 Azureus Software, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */


package com.biglybt.core.tag.impl;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.biglybt.core.Core;
import com.biglybt.core.CoreFactory;
import com.biglybt.core.CoreLifecycleAdapter;
import com.biglybt.core.CoreRunningListener;
import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.config.ParameterListener;
import com.biglybt.core.disk.DiskManager;
import com.biglybt.core.disk.DiskManagerFileInfo;
import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.download.DownloadManagerState;
import com.biglybt.core.peer.PEPeerManager;
import com.biglybt.core.tag.*;
import com.biglybt.core.tag.TagFeatureProperties.TagProperty;
import com.biglybt.core.tag.TagFeatureProperties.TagPropertyListener;
import com.biglybt.core.torrent.TOTorrent;
import com.biglybt.core.tracker.client.TRTrackerScraperResponse;
import com.biglybt.core.util.*;
import com.biglybt.core.util.average.AverageFactory;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadListener;
import com.biglybt.pifimpl.local.PluginCoreUtils;

public class
TagPropertyConstraintHandler
	implements TagTypeListener, DownloadListener
{
	private static final Object DM_FILE_FILE_NAMES = new Object();
	
	private final Core core;
	private final TagManagerImpl	tag_manager;

	
	private boolean		initialised;
	private boolean 	initial_assignment_complete;
	private boolean		stopping;

	final Map<Tag,TagConstraint>	constrained_tags 	= new ConcurrentHashMap<>();

	private boolean	dm_listener_added;

	final Map<Tag,Map<DownloadManager,Long>>			apply_history 		= new HashMap<>();

	private final AsyncDispatcher	dispatcher = new AsyncDispatcher( "tag:constraints" );

	private final FrequencyLimitedDispatcher	freq_lim_dispatcher =
		new FrequencyLimitedDispatcher(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					checkFreqLimUpdates();
				}
			},
			5000 );

	final IdentityHashMap<DownloadManager,List<TagConstraint>>	freq_lim_pending = new IdentityHashMap<>();


	private TimerEventPeriodic		timer;

	private
	TagPropertyConstraintHandler()
	{
		core				= null;
		tag_manager			= null;
	}

	protected
	TagPropertyConstraintHandler(
		Core _core,
		TagManagerImpl	_tm )
	{
		core			= _core;
		tag_manager		= _tm;

		if( core != null ){

			core.addLifecycleListener(
				new CoreLifecycleAdapter()
				{
					@Override
					public void
					stopping(Core core)
					{
						stopping	= true;
					}
				});
		}
		
		tag_manager.addTaggableLifecycleListener(
			Taggable.TT_DOWNLOAD,
			new TaggableLifecycleAdapter()
			{
				@Override
				public void
				initialised(
					List<Taggable>	current_taggables )
				{
					try{
						TagType tt_manual_download = tag_manager.getTagType( TagType.TT_DOWNLOAD_MANUAL );

						tt_manual_download.addTagTypeListener( TagPropertyConstraintHandler.this, true );

					}finally{

						CoreFactory.addCoreRunningListener(
							new CoreRunningListener()
							{
								@Override
								public void
								coreRunning(
									Core core )
								{
									synchronized( constrained_tags ){

										initialised = true;

										apply( core.getGlobalManager().getDownloadManagers(), true );
									}
								}
							});
					}
				}

				@Override
				public void
				taggableCreated(
					Taggable		taggable )
				{
					apply((DownloadManager)taggable, null, false );
				}
			});
	}

	private static Object	process_lock = new Object();
	private static int		processing_disabled_count;

	private static List<Object[]>	processing_queue = new ArrayList<>();

	public void
	setProcessingEnabled(
		boolean	enabled )
	{
		synchronized( process_lock ){

			if ( enabled ){

				processing_disabled_count--;

				if ( processing_disabled_count == 0 ){

					List<Object[]> to_do = new ArrayList<>(processing_queue);

					processing_queue.clear();

					for ( Object[] entry: to_do ){

						TagConstraint 	constraint 	= (TagConstraint)entry[0];
						Object			target		= entry[1];

						try{

							if ( target instanceof DownloadManager ){

								constraint.apply((DownloadManager)target);

							}else{

								constraint.apply((List<DownloadManager>)target);
							}
						}catch( Throwable e ){

							Debug.out( e );
						}
					}
				}
			}else{

				processing_disabled_count++;
			}
		}
	}

	private static boolean
	canProcess(
		TagConstraint		constraint,
		DownloadManager		dm )
	{
		synchronized( process_lock ){

			if ( processing_disabled_count == 0 ){

				return( true );

			}else{

				processing_queue.add( new Object[]{ constraint, dm });

				return( false );
			}
		}
	}

	private static boolean
	canProcess(
		TagConstraint				constraint,
		List<DownloadManager>		dms )
	{
		synchronized( process_lock ){

			if ( processing_disabled_count == 0 ){

				return( true );

			}else{

				processing_queue.add( new Object[]{ constraint, dms });

				return( false );
			}
		}
	}

	@Override
	public void
	tagTypeChanged(
		TagType		tag_type )
	{
	}

	@Override
	public void 
	tagEventOccurred(
		TagEvent event ) 
	{
		int	type = event.getEventType();
		
		Tag	tag = event.getTag();
		
		if ( type == TagEvent.ET_TAG_ADDED ){
			
			tagAdded( tag );
			
		}else if ( type == TagEvent.ET_TAG_REMOVED ){
			
			tagRemoved( tag );
			
		}else if ( type == TagEvent.ET_TAG_METADATA_CHANGED ){
			
			TagConstraint tc = constrained_tags.get( tag );
			
			if ( tc != null ){
				
				tc.checkStuff();
			}
		}
	}

	public void
	tagAdded(
		Tag			tag )
	{
		TagFeatureProperties tfp = (TagFeatureProperties)tag;

		TagProperty prop = tfp.getProperty( TagFeatureProperties.PR_CONSTRAINT );

		if ( prop != null ){

			prop.addListener(
				new TagPropertyListener()
				{
					@Override
					public void
					propertyChanged(
						TagProperty		property )
					{
						handleProperty( property );
					}

					@Override
					public void
					propertySync(
						TagProperty		property )
					{
					}
				});

			handleProperty( prop );
		}

		tag.addTagListener(
			new TagListener()
			{
				@Override
				public void
				taggableSync(
					Tag tag )
				{
				}

				@Override
				public void
				taggableRemoved(
					Tag 		tag,
					Taggable 	tagged )
				{
					apply((DownloadManager)tagged, tag, true );
				}

				@Override
				public void
				taggableAdded(
					Tag 		tag,
					Taggable 	tagged )
				{
					apply((DownloadManager)tagged, tag, true );
				}
			}, false );
	}

	private void
	checkTimer()
	{
			// already synchronized on constrainted_tags by callers

		if ( constrained_tags.size() > 0 ){

			if ( timer == null ){

				timer =
					SimpleTimer.addPeriodicEvent(
						"tag:constraint:timer",
						30*1000,
						new TimerEventPerformer() {

							@Override
							public void
							perform(
								TimerEvent event)
							{
								apply_history.clear();

								apply();
							}
						});

				CoreFactory.addCoreRunningListener(
					new CoreRunningListener()
					{
						@Override
						public void
						coreRunning(
							Core core )
						{
							synchronized( constrained_tags ){

								if ( timer != null ){

									core.getPluginManager().getDefaultPluginInterface().getDownloadManager().getGlobalDownloadEventNotifier().addListener( TagPropertyConstraintHandler.this );

									dm_listener_added = true;
								}
							}
						}
					});
			}

		}else if ( timer != null ){

			timer.cancel();

			timer = null;

			if ( dm_listener_added ){

				core.getPluginManager().getDefaultPluginInterface().getDownloadManager().getGlobalDownloadEventNotifier().removeListener( this );
			}

			apply_history.clear();
		}
	}

	private void
	checkFreqLimUpdates()
	{
		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					synchronized( freq_lim_pending ){

						for ( Map.Entry<DownloadManager,List<TagConstraint>> entry: freq_lim_pending.entrySet()){

							for ( TagConstraint con: entry.getValue()){

								con.apply( entry.getKey());
							}
						}

						freq_lim_pending.clear();
					}
				}
			});
	}

	@Override
	public void
	stateChanged(
		Download		download,
		int				old_state,
		int				new_state )
	{
		List<TagConstraint>	interesting = new ArrayList<>();

		synchronized( constrained_tags ){

			if ( !initialised ){

				return;
			}

			for ( TagConstraint tc: constrained_tags.values()){

				if ( tc.dependOnDownloadState()){

					interesting.add( tc );
				}
			}
		}

		if ( interesting.size() > 0 ){

			DownloadManager dm = PluginCoreUtils.unwrap( download );

			synchronized( freq_lim_pending ){

				freq_lim_pending.put( dm, interesting );
			}

			freq_lim_dispatcher.dispatch();
		}
	}

	@Override
	public void
	positionChanged(
		Download	download,
		int 		oldPosition,
		int 		newPosition )
	{
	}

	protected String
	getTagStatus(
		Tag	tag )
	{
		TagConstraint tc = constrained_tags.get( tag );
			
		if ( tc != null ){
			
			return( tc.getStatus());
		}
		
		return( null );
	}
	
	protected List<Tag>
	getDependsOnTags(
		Tag	tag )
	{
		TagConstraint tc = constrained_tags.get( tag );
		
		if ( tc != null ){
			
			return( tc.getDependsOnTags());
		}
		
		return( Collections.emptyList());
	}
	
	public void
	tagRemoved(
		Tag			tag )
	{
		synchronized( constrained_tags ){

			if ( constrained_tags.containsKey( tag )){

				constrained_tags.remove( tag );

				checkTimer();
			}
		}
	}

	private boolean
	isStopping()
	{
		return( stopping );
	}

	private void
	handleProperty(
		TagProperty		property )
	{
		Tag	tag = property.getTag();

		synchronized( constrained_tags ){

			boolean enabled = property.isEnabled();
			
			String[] value = property.getStringList();

			String 	constraint;
			String	options;

			if ( value == null ){

				constraint 	= "";
				options		= "";

			}else{

				constraint 	= value.length>0&&value[0]!=null?value[0].trim():"";
				options		= value.length>1&&value[1]!=null?value[1].trim():"";
			}

			if ( constraint.length() == 0 ){

				if ( constrained_tags.containsKey( tag )){

					constrained_tags.remove( tag );
				}
			}else{

				TagConstraint con = constrained_tags.get( tag );

				
				if (	con != null && 
						con.getConstraint().equals( constraint ) && 
						con.getOptions().equals( options ) &&
						con.isEnabled() == enabled ){

					return;
				}

				con = new TagConstraint( this, tag, constraint, options, enabled );

				constrained_tags.put( tag, con );

				if ( initialised ){

					apply( con );
				}
			}

			checkTimer();
		}
	}

	private void
	apply(
		final DownloadManager				dm,
		Tag									related_tag,
		boolean								auto )
	{
		if ( dm.isDestroyed()){

			return;
		}

		synchronized( constrained_tags ){

			if ( constrained_tags.size() == 0 || !initialised ){

				return;
			}

			if ( auto && !initial_assignment_complete ){

				return;
			}
		}

		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					List<TagConstraint>	cons;

					synchronized( constrained_tags ){

						cons = new ArrayList<>(constrained_tags.values());
					}

					for ( TagConstraint con: cons ){

						con.apply( dm );
					}
				}
			});
	}

	private void
	apply(
		final List<DownloadManager>		dms,
		final boolean					initial_assignment )
	{
		synchronized( constrained_tags ){

			if ( constrained_tags.size() == 0 || !initialised ){

				return;
			}
		}

		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					List<TagConstraint>	cons;

					synchronized( constrained_tags ){

						cons = new ArrayList<>(constrained_tags.values());
					}

						// set up initial constraint tagged state without following implications

					for ( TagConstraint con: cons ){

						con.apply( dms );
					}

					if ( initial_assignment ){

						synchronized( constrained_tags ){

							initial_assignment_complete = true;
						}

							// go over them one more time to pick up consequential constraints

						for ( TagConstraint con: cons ){

							con.apply( dms );
						}
					}
				}
			});
	}

	private void
	apply(
		final TagConstraint		constraint )
	{
		synchronized( constrained_tags ){

			if ( !initialised ){

				return;
			}
		}

		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					List<DownloadManager> dms = core.getGlobalManager().getDownloadManagers();

					constraint.apply( dms );
				}
			});
	}

	private void
	apply()
	{
		synchronized( constrained_tags ){

			if ( constrained_tags.size() == 0 || !initialised ){

				return;
			}
		}

		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					List<DownloadManager> dms = core.getGlobalManager().getDownloadManagers();

					List<TagConstraint>	cons;

					synchronized( constrained_tags ){

						cons = new ArrayList<>(constrained_tags.values());
					}

					for ( TagConstraint con: cons ){

						con.apply( dms );
					}
				}
			});
	}

	private TagConstraint.ConstraintExpr
	compileConstraint(
		String		expr )
	{
		return( new TagConstraint( this, null, expr, null, true ).expr );
	}

	private static Pattern comp_op_pattern = Pattern.compile( "(.+?)(==|!=|>=|>|<=|<)(.+)");
	
	private static Map<String,String>	comp_op_map = new HashMap<>();
	
	static{
		comp_op_map.put( "==", "isEQ" );
		comp_op_map.put( "!=", "isNEQ" );
		comp_op_map.put( ">=", "isGE" );
		comp_op_map.put( "<=", "isLE" );
		comp_op_map.put( ">",  "isGT" );
		comp_op_map.put( "<",  "isLT" );
	}
	
	private static Map<String,Object[]>	config_value_cache = new ConcurrentHashMap<String, Object[]>();
	
	private static Map<String,String[]>	config_key_map = new HashMap<>();
	
	private static final String	CONFIG_FLOAT = "float";
	
	static{
		String[][] entries = {
				{ "queue.seeding.ignore.share.ratio", CONFIG_FLOAT, "Stop Ratio" },	
		};
		
		ParameterListener listener = 
			new ParameterListener()
			{
				@Override
				public void 
				parameterChanged(
					String parameterName)
				{	
					config_value_cache.clear();
				}
			};
			
		for ( String[] entry: entries ){
			
			config_key_map.put( entry[0], new String[]{ entry[1], entry[2] });
			
			COConfigurationManager.addParameterListener( entry[2], listener );	
		}
	}
	
	private static class
	TagConstraint
	{
		private final TagPropertyConstraintHandler	handler;
		private final Tag							tag;
		private final String						constraint;
		private final boolean						enabled;
		
		private final boolean		auto_add;
		private final boolean		auto_remove;

		private final ConstraintExpr	expr;

		private boolean	depends_on_download_state;
		private int		depends_on_level			= DEP_STATIC;

		private List<Tag>		dependent_on_tags;
		private boolean			must_check_dependencies;
		
		private Average			activity_average = Average.getInstance( 1000, 60 );
		
		private
		TagConstraint(
			TagPropertyConstraintHandler	_handler,
			Tag								_tag,
			String							_constraint,
			String							options,
			boolean							_enabled )
		{
			handler		= _handler;
			tag			= _tag;
			constraint	= _constraint;
			enabled		= _enabled;
			
			if ( options == null ){

				auto_add	= true;
				auto_remove	= true;

			}else{
					// 0 = add+remove; 1 = add only; 2 = remove only

				auto_add 	= !options.contains( "am=2;" );
				auto_remove = !options.contains( "am=1;" );
			}
			
			checkStuff();			
			
			ConstraintExpr compiled_expr = null;

			if ( tag != null ){
			
				tag.setTransientProperty( Tag.TP_CONSTRAINT_ERROR, null );
			}
			
			try{
				compiled_expr = compileStart( constraint, new HashMap<String,ConstraintExpr>());

				// System.out.println( "Compiled:\n" + constraint + " \n->\n" + compiled_expr.getString());
				
			}catch( Throwable e ){

				Debug.out( e );
				
				setError( "Invalid constraint: " + Debug.getNestedExceptionMessage( e ));

			}finally{

				expr = compiled_expr;
			}
		}

		private String
		getStatus()
		{
			String result = activity_average.getAverage() + "/" +  TimeFormatter.getLongSuffix( TimeFormatter.TS_SECOND );
			
			if ( Constants.IS_CVS_VERSION ){
				
				result +=  ", " + "DS=" + depends_on_download_state + ", DL=" + depends_on_level;
			}
			
			return( result );
		}
		
		private List<Tag>
		getDependsOnTags()
		{
			return( dependent_on_tags );
		}
		
		private void
		checkStuff()
		{
			// we're only bothered about assignments to tags that can have significant side-effects. Currently these are
			// 1) execute-on-assign tags
			// 2) tags with limits (and therefore removal policies such as 'delete download')

			if ( tag != null ){
				
				if (((TagFeatureExecOnAssign)tag).isAnyActionEnabled()){
					
					must_check_dependencies = true;
					
				}else if ( (((TagFeatureLimits)tag).getMaximumTaggables() > 0 )){
					
					must_check_dependencies = true;
					
				}else{
					
					must_check_dependencies = false;
				}
			}
		}
		
		private boolean
		isEnabled()
		{
			return( enabled );
		}
		private void
		setError(
			String		str )
		{
			tag.setTransientProperty( Tag.TP_CONSTRAINT_ERROR, str );
			
			Debug.out( str );
		}
		
		private boolean
		dependOnDownloadState()
		{
			return( depends_on_download_state );
		}			
		
		private ConstraintExpr
		compileStart(
			String						str,
			Map<String,ConstraintExpr>	context )
		{
			str = str.trim();

			if ( str.equalsIgnoreCase( "true" )){

				return( new ConstraintExprTrue());
			}

			char[] chars = str.toCharArray();

			boolean	in_quote 	= false;

			int	level 			= 0;
			int	bracket_start 	= 0;

			StringBuilder result = new StringBuilder( str.length());

			for ( int i=0;i<chars.length;i++){

				char c = chars[i];

				if ( GeneralUtils.isDoubleQuote( c )){

					if ( i == 0 || chars[i-1] != '\\' ){

						in_quote = !in_quote;
					}
				}

				if ( !in_quote ){

					if ( c == '(' ){

						level++;

						if ( level == 1 ){

							bracket_start = i+1;
						}
					}else if ( c == ')' ){

						level--;

						if ( level == 0 ){

							String bracket_text = new String( chars, bracket_start, i-bracket_start ).trim();

							if ( result.length() > 0 && Character.isLetterOrDigit( result.charAt( result.length()-1 ))){

									// function call

								String key = "{" + context.size() + "}";

								context.put( key, new ConstraintExprParams( bracket_text, context ));

								result.append( "(" ).append( key ).append( ")" );

							}else{

								ConstraintExpr sub_expr = compileStart( bracket_text, context );

								if ( sub_expr == null ){
									
									throw( new RuntimeException( "Failed to compile '" + bracket_text + "'" ));
								}
								
								String key = "{" + context.size() + "}";

								context.put(key, sub_expr );

								result.append( key );
							}
						}
					}else if ( level == 0 ){

						if ( !Character.isWhitespace( c )){

							result.append( c );
						}
					}
				}else if ( level == 0 ){

					result.append( c );

				}
			}

			if ( level != 0 ){

				throw( new RuntimeException( "Unmatched '(' in \"" + str + "\"" ));
			}

			if ( in_quote ){

				throw( new RuntimeException( "Unmatched '\"' in \"" + str + "\"" ));
			}

			return( compileBasic( result.toString(), context ));
		}

		private ConstraintExpr
		compileBasic(
			String						str,
			Map<String,ConstraintExpr>	context )
		{			
			if ( str.contains( "||" )){

				String[] bits = str.split( "\\|\\|" );

				return( new ConstraintExprOr( compile( bits, context )));

			}else if ( str.contains( "&&" )){

				String[] bits = str.split( "&&" );

				return( new ConstraintExprAnd( compile( bits, context )));

			}else if ( str.contains( "^" )){

				String[] bits = str.split( "\\^" );

				return( new ConstraintExprXor( compile( bits, context )));

			}else{
							
				Matcher m = comp_op_pattern.matcher( str );

				if ( m.find()){
							
					String lhs 	= m.group(1).trim();
					String op 	= m.group(2).trim();
					String rhs	= m.group(3).trim();
					
					ConstraintExprParams params = new ConstraintExprParams( lhs + "," + rhs, context );
					
					return( new ConstraintExprFunction( comp_op_map.get( op ), params ));
					
				}else if ( str.startsWith( "!" )){
	
					return( new ConstraintExprNot( compileBasic( str.substring(1).trim(), context )));
	
				}else if ( str.startsWith( "{" )){
	
					ConstraintExpr val = context.get( str );
						
					if ( val == null ){
						
						throw( new RuntimeException( "Failed to compile '" + str + "'" ));
					}
						
					return( val );
	 
				}else{
	
					int	pos = str.indexOf( '(' );
	
					if ( pos > 0 && str.endsWith( ")" )){
	
						String func = str.substring( 0, pos );
	
						String key = str.substring( pos+1, str.length() - 1 ).trim();
	
						ConstraintExprParams params = (ConstraintExprParams)context.get( key );
	
						return( new ConstraintExprFunction( func, params ));
	
					}else{
	
						throw( new RuntimeException( "Unsupported construct: " + str ));
					}
				}
			}
		}

		private ConstraintExpr[]
		compile(
			String[]					bits,
			Map<String,ConstraintExpr>	context )
		{
			ConstraintExpr[] res = new ConstraintExpr[ bits.length ];

			for ( int i=0; i<bits.length;i++){

				res[i] = compileBasic( bits[i].trim(), context );
			}

			return( res );
		}

		private String
		getConstraint()
		{
			return( constraint );
		}

		private String
		getOptions()
		{
			if ( auto_add ){
				return( "am=1;" );
			}else if ( auto_remove ){
				return( "am=2;" );
			}else{
				return( "am=0;" );
			}
		}

		private void
		apply(
			DownloadManager			dm )
		{
			if ( ignoreDownload( dm )){

				return;
			}

			if ( expr == null ){

				return;
			}

			if ( handler.isStopping()){

				return;
			}

			if ( !canProcess( this, dm )){

				return;
			}

			Set<Taggable>	existing = tag.getTagged();

			applySupport( existing, dm );
		}

		private void
		apply(
			List<DownloadManager>	dms )
		{
			if ( expr == null ){

				return;
			}

			if ( handler.isStopping()){

				return;
			}

			if ( !canProcess( this, dms )){

				return;
			}

			Set<Taggable>	existing = tag.getTagged();

			for ( DownloadManager dm: dms ){

				if ( handler.isStopping()){

					return;
					
				}else  if ( ignoreDownload( dm )){

					continue;
				}

				applySupport( existing, dm );
			}
		}

		private void
		applySupport(
			Set<Taggable>		existing,
			DownloadManager		dm )
		{
			applySupport2( existing, dm, must_check_dependencies, null );
		}
		
		private void
		applySupport2(
			Set<Taggable>		existing,
			DownloadManager		dm,
			boolean				check_dependencies,
			Set<TagConstraint>	checked )
		{
			if ( check_dependencies && checked != null && checked.contains( this )){
				
				return;
			}
			
			if ( testConstraint( dm )){

				if ( auto_add ){

					if ( !existing.contains( dm )){


						if ( check_dependencies && dependent_on_tags != null ){
						
							boolean	recheck = false;
							
							for ( Tag t: dependent_on_tags ){
								
								TagConstraint dep = handler.constrained_tags.get( t );
								
								if ( dep != null ){
									
									if ( checked == null ){
										
										checked = new HashSet<>();
									}
									
									try{
										checked.add( this );
										
										//System.out.println( "checking sub-dep " + dep + ", checked=" + checked );
										
										dep.applySupport2( existing, dm, true, checked );
									
									}finally{
										
										checked.remove( this );
									}
									
									recheck = true;
								}
							}
							
							if ( recheck ){
								
								applySupport2( existing, dm, false, checked );
									
								return;
							}
						}
						
						if ( handler.isStopping()){

							return;
						}

						if ( canAddTaggable( dm )){

							tag.addTaggable( dm );
						}
					}
				}
			}else{

				if ( auto_remove ){

					if ( existing.contains( dm )){

						if ( handler.isStopping()){

							return;
						}

						tag.removeTaggable( dm );
					}
				}
			}
		}
		
		private boolean
		ignoreDownload(
			DownloadManager dm )
		{
			if ( dm.isDestroyed()) {
				
				return( true );
				
				// 2018/10/25 - can't think of any good reason to skip non-persistent downloads, other tag
				// operations don't
				
			//}else if ( !dm.isPersistent()) {
			
				//return( !dm.getDownloadState().getFlag(DownloadManagerState.FLAG_METADATA_DOWNLOAD ));
				
			}else{
				
				return( false );
			}
		}

		private boolean
		canAddTaggable(
			DownloadManager		dm )
		{
			long	now = SystemTime.getMonotonousTime();

			Map<DownloadManager,Long> recent_dms = handler.apply_history.get( tag );

			if ( recent_dms != null ){

				Long time = recent_dms.get( dm );

				if ( time != null && now - time < 1000 ){

					System.out.println( "Not applying constraint as too recently actioned: " + dm.getDisplayName() + "/" + tag.getTagName( true ));

					return( false );
				}
			}

			if ( recent_dms == null ){

				recent_dms = new HashMap<>();

				handler.apply_history.put( tag, recent_dms );
			}

			recent_dms.put( dm, now );

			return( true );
		}

		private boolean
		testConstraint(
			DownloadManager	dm )
		{
			if ( enabled ){
								
				activity_average.addValue( 1 );
				
				List<Tag> dm_tags = handler.tag_manager.getTagsForTaggable( dm );
	
				return( (Boolean)expr.eval( dm, dm_tags ));
				
			}else{
				
				return( false );
			}
		}

		private interface
		ConstraintExpr
		{
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags );

			public String
			getString();
		}

		private static class
		ConstraintExprTrue
			implements ConstraintExpr
		{
			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				return( true );
			}

			@Override
			public String
			getString()
			{
				return( "true" );
			}
		}

		private class
		ConstraintExprParams
			implements  ConstraintExpr
		{
			private final String						value;
			private final Map<String,ConstraintExpr>	context;
			
			private
			ConstraintExprParams(
				String						_value,
				Map<String,ConstraintExpr>	_context )
			{
				value		= _value.trim();
				context		= _context;
				
				try{
					Object[] args = getValues();
					
					for ( Object obj: args ){
						
						if ( obj instanceof String ){
							
							int[] kw_details = keyword_map.get((String)obj);
							
							if ( kw_details != null ){
								
								depends_on_level = Math.max( depends_on_level, kw_details[1] );
							}
						}
					}
				}catch( Throwable e ){
				}
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				return( false );
			}

			public Object[]
			getValues()
			{
				if ( value.length() == 0 ){

					return( new String[0]);
					
				}else if ( !value.contains( "," )){

						// guaranteed single argument
					
					if ( GeneralUtils.startsWithDoubleQuote( value )){ 
						
						// string literal
						
					}else if ( value.startsWith( "{" )){
						
						return( new Object[]{ dereference( value )});
						
					}else if ( value.contains( "(" )){
						
						return( new Object[]{  compileStart(value, context )});
					}
					
					return( new Object[]{ value });

				}else{

					char[]	chars = value.toCharArray();

					boolean in_quote = false;

					List<Object>	params = new ArrayList<>(16);

					StringBuilder current_param = new StringBuilder( value.length());

					for (int i=0;i<chars.length;i++){

						char c = chars[i];

						if ( GeneralUtils.isDoubleQuote( c )){

							if ( i == 0 || chars[i-1] != '\\' ){

								in_quote = !in_quote;
							}
						}

						if ( c == ',' && !in_quote ){

							params.add( current_param.toString());

							current_param.setLength( 0 );

						}else{

							if ( in_quote || !Character.isWhitespace( c )){

								current_param.append( c );
							}
						}
					}

					params.add( current_param.toString());

					for ( int i=0;i<params.size();i++){
						
						String p = (String)params.get( i );
						
						if ( GeneralUtils.startsWithDoubleQuote( p )){
							
							// string literal
							
						}else if ( p.startsWith( "{" )){
							
							params.set(i, dereference( p ));
							
						}else if ( p.contains( "(" )){
							
							params.set(i,compileStart(p, context ));
						}
					}
					
					return( params.toArray( new Object[ params.size()]));
				}
			}

			private Object
			dereference(
				String	key )
			{
				Object obj = context.get( key );
				
				if ( obj == null ){
					
					throw( new RuntimeException( "Reference " + key + " not found" ));
				}
			
				if ( obj instanceof ConstraintExprParams ){
					
					ConstraintExprParams params = (ConstraintExprParams)obj;
					
					Object[] args = params.getValues();
					
					if ( args.length != 1 ){
						
						throw( new RuntimeException( "Reference " + key + " resolved incorrectly" ));
					}
					
					return( args[0] );
					
				}
				
				return( obj );
			}
			
			@Override
			public String
			getString()
			{
				Object[] params = getValues();
				
				String str = "";
				
				for ( Object obj: params ){
					
					str += (str.isEmpty()?"":",") + (obj instanceof ConstraintExpr?((ConstraintExpr)obj).getString():obj);
				}
				
				return( str );
			}
		}

		private static class
		ConstraintExprNot
			implements  ConstraintExpr
		{
			private final ConstraintExpr expr;

			private
			ConstraintExprNot(
				ConstraintExpr	e )
			{
				expr = e;
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				return( ! (Boolean)expr.eval( dm, tags ));
			}

			@Override
			public String
			getString()
			{
				return( "!(" + expr.getString() + ")");
			}
		}

		private static class
		ConstraintExprOr
			implements  ConstraintExpr
		{
			private final ConstraintExpr[]	exprs;

			private
			ConstraintExprOr(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				for ( ConstraintExpr expr: exprs ){

					if (  (Boolean)expr.eval( dm, tags )){

						return( true );
					}
				}

				return( false );
			}

			@Override
			public String
			getString()
			{
				String res = "";

				for ( int i=0;i<exprs.length;i++){

					res += (i==0?"":"||") + exprs[i].getString();
				}

				return( "(" + res + ")" );
			}
		}

		private static class
		ConstraintExprAnd
			implements  ConstraintExpr
		{
			private final ConstraintExpr[]	exprs;

			private
			ConstraintExprAnd(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				for ( ConstraintExpr expr: exprs ){

					if ( ! (Boolean)expr.eval( dm, tags )){

						return( false );
					}
				}

				return( true );
			}

			@Override
			public String
			getString()
			{
				String res = "";

				for ( int i=0;i<exprs.length;i++){

					res += (i==0?"":"&&") + exprs[i].getString();
				}

				return( "(" + res + ")" );
			}
		}

		private static class
		ConstraintExprXor
			implements  ConstraintExpr
		{
			private final ConstraintExpr[]	exprs;

			private
			ConstraintExprXor(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;

				if ( exprs.length < 2 ){

					throw( new RuntimeException( "Two or more arguments required for ^" ));
				}
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				boolean res =  (Boolean)exprs[0].eval( dm, tags );

				for ( int i=1;i<exprs.length;i++){

					res = res ^  (Boolean)exprs[i].eval( dm, tags );
				}

				return( res );
			}

			@Override
			public String
			getString()
			{
				String res = "";

				for ( int i=0;i<exprs.length;i++){

					res += (i==0?"":"^") + exprs[i].getString();
				}

				return( "(" + res + ")" );
			}
		}
		
		private static final int FT_HAS_TAG		= 1;
		private static final int FT_IS_PRIVATE	= 2;

		private static final int FT_GE			= 3;
		private static final int FT_GT			= 4;
		private static final int FT_LE			= 5;
		private static final int FT_LT			= 6;
		private static final int FT_EQ			= 7;
		private static final int FT_NEQ			= 8;

		private static final int FT_CONTAINS	= 9;
		private static final int FT_MATCHES		= 10;

		private static final int FT_HAS_NET			= 11;
		private static final int FT_IS_COMPLETE		= 12;
		private static final int FT_CAN_ARCHIVE		= 13;
		private static final int FT_IS_FORCE_START	= 14;
		private static final int FT_JAVASCRIPT		= 15;
		private static final int FT_IS_CHECKING		= 16;
		private static final int FT_IS_STOPPED		= 17;
		private static final int FT_IS_PAUSED		= 18;
		private static final int FT_IS_ERROR		= 19;
		private static final int FT_IS_MAGNET		= 20;
		private static final int FT_IS_LOW_NOISE	= 21;
		private static final int FT_COUNT_TAG		= 22;
		private static final int FT_HAS_TAG_GROUP	= 23;
		private static final int FT_HOURS_TO_SECS	= 24;
		private static final int FT_DAYS_TO_SECS	= 25;
		private static final int FT_WEEKS_TO_SECS	= 26;
		private static final int FT_GET_CONFIG		= 27;

		
		private static final int	DEP_STATIC		= 0;
		private static final int	DEP_RUNNING		= 1;
		private static final int	DEP_TIME		= 2;
		
		static final Map<String,int[]>	keyword_map = new HashMap<>();

		private static final int	KW_SHARE_RATIO		= 0;
		private static final int	KW_AGE 				= 1;
		private static final int	KW_PERCENT 			= 2;
		private static final int	KW_DOWNLOADING_FOR 	= 3;
		private static final int	KW_SEEDING_FOR 		= 4;
		private static final int	KW_SWARM_MERGE 		= 5;
		private static final int	KW_LAST_ACTIVE 		= 6;
		private static final int	KW_SEED_COUNT 		= 7;
		private static final int	KW_PEER_COUNT 		= 8;
		private static final int	KW_SEED_PEER_RATIO 	= 9;
		private static final int	KW_RESUME_IN 		= 10;
		private static final int	KW_MIN_OF_HOUR 		= 11;
		private static final int	KW_HOUR_OF_DAY 		= 12;
		private static final int	KW_DAY_OF_WEEK 		= 13;
		private static final int	KW_TAG_AGE 			= 14;
		private static final int	KW_COMPLETED_AGE 	= 15;
		private static final int	KW_PEER_MAX_COMP 	= 16;
		private static final int	KW_PEER_AVERAGE_COMP 	= 17;
		private static final int	KW_LEECHER_MAX_COMP 	= 18;
		private static final int	KW_SIZE				 	= 19;
		private static final int	KW_SIZE_MB			 	= 20;
		private static final int	KW_SIZE_GB			 	= 21;
		private static final int	KW_FILE_COUNT		 	= 22;
		private static final int	KW_AVAILABILITY		 	= 23;
		private static final int	KW_UP_IDLE			 	= 24;
		private static final int	KW_DOWN_IDLE		 	= 25;
		private static final int	KW_DOWNLOADED		 	= 26;
		private static final int	KW_UPLOADED			 	= 27;
		private static final int	KW_NAME				 	= 28;
		private static final int	KW_FILE_NAMES		 	= 29;

		static{
			keyword_map.put( "shareratio", 				new int[]{KW_SHARE_RATIO,			DEP_RUNNING });
			keyword_map.put( "share_ratio", 			new int[]{KW_SHARE_RATIO,			DEP_RUNNING });
			keyword_map.put( "age",						new int[]{KW_AGE,					DEP_TIME });
			keyword_map.put( "percent", 				new int[]{KW_PERCENT,				DEP_RUNNING });
			keyword_map.put( "downloadingfor", 			new int[]{KW_DOWNLOADING_FOR,		DEP_RUNNING });
			keyword_map.put( "downloading_for", 		new int[]{KW_DOWNLOADING_FOR,		DEP_RUNNING });
			keyword_map.put( "seedingfor", 				new int[]{KW_SEEDING_FOR,			DEP_RUNNING });
			keyword_map.put( "seeding_for", 			new int[]{KW_SEEDING_FOR,			DEP_RUNNING });
			keyword_map.put( "swarmmergebytes", 		new int[]{KW_SWARM_MERGE,			DEP_RUNNING });
			keyword_map.put( "swarm_merge_bytes", 		new int[]{KW_SWARM_MERGE,			DEP_RUNNING });
			keyword_map.put( "lastactive", 				new int[]{KW_LAST_ACTIVE,			DEP_RUNNING });
			keyword_map.put( "last_active", 			new int[]{KW_LAST_ACTIVE,			DEP_RUNNING });
			keyword_map.put( "seedcount", 				new int[]{KW_SEED_COUNT,			DEP_TIME  });
			keyword_map.put( "seed_count", 				new int[]{KW_SEED_COUNT,			DEP_TIME });
			keyword_map.put( "peercount", 				new int[]{KW_PEER_COUNT,			DEP_TIME });
			keyword_map.put( "peer_count", 				new int[]{KW_PEER_COUNT,			DEP_TIME });
			keyword_map.put( "seedpeerratio", 			new int[]{KW_SEED_PEER_RATIO,		DEP_TIME });
			keyword_map.put( "seed_peer_ratio", 		new int[]{KW_SEED_PEER_RATIO,		DEP_TIME });
			keyword_map.put( "resumein", 				new int[]{KW_RESUME_IN,				DEP_TIME });
			keyword_map.put( "resume_in",				new int[]{KW_RESUME_IN,				DEP_TIME });

			keyword_map.put( "minofhour", 				new int[]{KW_MIN_OF_HOUR,			DEP_TIME });
			keyword_map.put( "min_of_hour",				new int[]{KW_MIN_OF_HOUR,			DEP_TIME });
			keyword_map.put( "hourofday", 				new int[]{KW_HOUR_OF_DAY,			DEP_TIME });
			keyword_map.put( "hour_of_day", 			new int[]{KW_HOUR_OF_DAY,			DEP_TIME });
			keyword_map.put( "dayofweek", 				new int[]{KW_DAY_OF_WEEK,			DEP_TIME });
			keyword_map.put( "day_of_week", 			new int[]{KW_DAY_OF_WEEK,			DEP_TIME });
			keyword_map.put( "tagage", 					new int[]{KW_TAG_AGE,				DEP_TIME });
			keyword_map.put( "tag_age", 				new int[]{KW_TAG_AGE,				DEP_TIME });
			keyword_map.put( "completedage", 			new int[]{KW_COMPLETED_AGE,			DEP_TIME });
			keyword_map.put( "completed_age", 			new int[]{KW_COMPLETED_AGE,			DEP_TIME });

			keyword_map.put( "peermaxcompletion", 		new int[]{KW_PEER_MAX_COMP,			DEP_RUNNING });
			keyword_map.put( "peer_max_completion", 	new int[]{KW_PEER_MAX_COMP,			DEP_RUNNING });
			
			keyword_map.put( "leechmaxcompletion", 		new int[]{KW_LEECHER_MAX_COMP,		DEP_RUNNING });
			keyword_map.put( "leech_max_completion", 	new int[]{KW_LEECHER_MAX_COMP,		DEP_RUNNING });
			keyword_map.put( "leechermaxcompletion", 	new int[]{KW_LEECHER_MAX_COMP,		DEP_RUNNING });
			keyword_map.put( "leecher_max_completion", 	new int[]{KW_LEECHER_MAX_COMP,		DEP_RUNNING });
			
			keyword_map.put( "peeraveragecompletion", 	new int[]{KW_PEER_AVERAGE_COMP,		DEP_RUNNING });
			keyword_map.put( "peer_average_completion", new int[]{KW_PEER_AVERAGE_COMP,		DEP_RUNNING });
			
			keyword_map.put( "size", 					new int[]{KW_SIZE,					DEP_STATIC });
			keyword_map.put( "sizemb", 					new int[]{KW_SIZE_MB,				DEP_STATIC });
			keyword_map.put( "size_mb", 				new int[]{KW_SIZE_MB,				DEP_STATIC });
			keyword_map.put( "sizegb", 					new int[]{KW_SIZE_GB,				DEP_STATIC });
			keyword_map.put( "size_gb", 				new int[]{KW_SIZE_GB,				DEP_STATIC });
			
			keyword_map.put( "filecount", 				new int[]{KW_FILE_COUNT,			DEP_STATIC });
			keyword_map.put( "file_count", 				new int[]{KW_FILE_COUNT,			DEP_STATIC });
			
			keyword_map.put( "availability", 			new int[]{KW_AVAILABILITY,			DEP_RUNNING });
			
			keyword_map.put( "upidle", 					new int[]{KW_UP_IDLE,				DEP_RUNNING });
			keyword_map.put( "up_idle", 				new int[]{KW_UP_IDLE,				DEP_RUNNING });
			keyword_map.put( "downidle", 				new int[]{KW_DOWN_IDLE,				DEP_RUNNING });
			keyword_map.put( "down_idle", 				new int[]{KW_DOWN_IDLE, 			DEP_RUNNING });

			keyword_map.put( "downloaded", 				new int[]{KW_DOWNLOADED,			DEP_RUNNING });
			keyword_map.put( "uploaded", 				new int[]{KW_UPLOADED,				DEP_RUNNING });
			
			keyword_map.put( "name", 					new int[]{KW_NAME,					DEP_STATIC });
			keyword_map.put( "file_names", 				new int[]{KW_FILE_NAMES,			DEP_STATIC });
		}

		private class
		ConstraintExprFunction
			implements  ConstraintExpr
		{
			private	final String 				func_name;
			private final ConstraintExprParams	params_expr;
			private final Object[]				params;

			private final int	fn_type;

			private IdentityHashMap<DownloadManager, Object[]>	matches_cache = new IdentityHashMap<>();
						
			private
			ConstraintExprFunction(
				String 					_func_name,
				ConstraintExprParams	_params )
			{
				func_name	= _func_name;
				params_expr	= _params;

				params		= _params.getValues();

				boolean	params_ok = false;

				if ( func_name.equals( "hasTag" )){

					fn_type = FT_HAS_TAG;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );

					if ( params_ok ){
						
						String tag_name = (String)params[0];
						
						if ( handler.tag_manager != null ){
							
							List<Tag> tags = handler.tag_manager.getTagsByName( tag_name, true );
							
							if ( tags.isEmpty()){
								
								throw( new RuntimeException( "Tag '" + tag_name + "' not found" ));
							}
							
							for ( Tag t: tags ){
								
								if ( t.getTagType().hasTagTypeFeature( TagFeature.TF_PROPERTIES )){
									
									if ( dependent_on_tags == null ){
								
										dependent_on_tags = new ArrayList<Tag>(5);
									}
								
									dependent_on_tags.add( t );
								}
							}
						}
					}
				}else if ( func_name.equals( "hasNet" )){

					fn_type = FT_HAS_NET;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );

					if ( params_ok ){

						params[0] = AENetworkClassifier.internalise((String)params[0]);

						params_ok = params[0] != null;
					}
				}else if ( func_name.equals( "isPrivate" )){

					fn_type = FT_IS_PRIVATE;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isForceStart" )){

					fn_type = FT_IS_FORCE_START;

					depends_on_download_state = true;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isChecking" )){

					fn_type = FT_IS_CHECKING;

					depends_on_download_state = true;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isComplete" )){

					fn_type = FT_IS_COMPLETE;

					depends_on_download_state = true;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isStopped" )){

						fn_type = FT_IS_STOPPED;

						depends_on_download_state = true;

						params_ok = params.length == 0;

				}else if ( func_name.equals( "isError" )){

					fn_type = FT_IS_ERROR;

					depends_on_download_state = true;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isPaused" )){

					fn_type = FT_IS_PAUSED;

					depends_on_download_state = true;

					params_ok = params.length == 0;
					
				}else if ( func_name.equals( "isMagnet" )){

					fn_type = FT_IS_MAGNET;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isLowNoise" )){

					fn_type = FT_IS_LOW_NOISE;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "canArchive" )){

					fn_type = FT_CAN_ARCHIVE;

					params_ok = params.length == 0;

				}else if ( func_name.equals( "isGE" )){

					fn_type = FT_GE;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "isGT" )){

					fn_type = FT_GT;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "isLE" )){

					fn_type = FT_LE;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "isLT" )){

					fn_type = FT_LT;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "isEQ" )){

					fn_type = FT_EQ;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "isNEQ" )){

					fn_type = FT_NEQ;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "contains" )){

					fn_type = FT_CONTAINS;

					params_ok = params.length == 2;

				}else if ( func_name.equals( "matches" )){

					fn_type = FT_MATCHES;

					params_ok = params.length == 2 && getStringLiteral( params, 1 );

					if ( params_ok ){
						
						try{
							Pattern.compile((String)params[1], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE );
														
						}catch( Throwable e ) {
							
							tag.setTransientProperty( Tag.TP_CONSTRAINT_ERROR, "Invalid constraint pattern: " + params[1] + ": " + e.getMessage());

						}
					}
				}else if ( func_name.equals( "javascript" )){

					fn_type = FT_JAVASCRIPT;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );

					depends_on_download_state = true;	// dunno so let's assume so

				}else if ( func_name.equals( "countTag" )){

					fn_type = FT_COUNT_TAG;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );
					
				}else if ( func_name.equals( "hasTagGroup" )){

					fn_type = FT_HAS_TAG_GROUP;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );

				}else if ( func_name.equals( "hoursToSeconds" ) || func_name.equals( "htos" ) || func_name.equals( "h2s" )){

					fn_type = FT_HOURS_TO_SECS;

					params_ok = params.length == 1 && getNumericLiteral( params, 0 );
					
				}else if ( func_name.equals( "daysToSeconds" ) || func_name.equals( "dtos" ) || func_name.equals( "d2s" )){

					fn_type = FT_DAYS_TO_SECS;

					params_ok = params.length == 1 && getNumericLiteral( params, 0 );
					
				}else if ( func_name.equals( "weeksToSeconds" ) || func_name.equals( "wtos" ) || func_name.equals( "w2s" )){

					fn_type = FT_WEEKS_TO_SECS;

					params_ok = params.length == 1 && getNumericLiteral( params, 0 );

				}else if ( func_name.equals( "getConfig" )){

					fn_type = FT_GET_CONFIG;

					params_ok = params.length == 1 && getStringLiteral( params, 0 );
					
					if ( params_ok ){
						
						String key = (String)params[0];
						
						key = key.toLowerCase( Locale.US );
						
						params[0] = key;
								
						if ( !config_key_map.containsKey( key )){
							
							throw( new RuntimeException( "Unsupported configuration parameter: " + key ));
						}
					}
				}else{

					throw( new RuntimeException( "Unsupported function '" + func_name + "'" ));
				}

				if ( !params_ok ){

					throw( new RuntimeException( "Invalid parameters for function '" + func_name + "': " + params_expr.getString()));

				}
			}

			@Override
			public Object
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				switch( fn_type ){
					case FT_HAS_TAG:{

						String tag_name = (String)params[0];

						for ( Tag t: tags ){

							if ( t.getTagName( true ).equals( tag_name )){

								return( true );
							}
						}

						return( false );
					}
					case FT_HAS_TAG_GROUP:{

						String group_name = (String)params[0];

						for ( Tag t: tags ){

							String group = t.getGroup();
							
							if ( group != null && group.equals( group_name )){

								return( true );
							}
						}

						return( false );
					}
					case FT_COUNT_TAG:{
						
						String tag_name = (String)params[0];
						
						List<Tag> fred = handler.tag_manager.lookupTagsByName( tag_name );
						
						if ( fred.isEmpty()){
							
							tag.setTransientProperty( Tag.TP_CONSTRAINT_ERROR, "Tag '" + tag_name + "' not found" );
							
							return( 0 );
							
						}else{
							
							return( fred.get(0).getTaggedCount());
						}
					}
					case FT_HAS_NET:{

						String net_name = (String)params[0];

						if ( net_name != null ){

							String[] nets = dm.getDownloadState().getNetworks();

							if ( nets != null ){

								for ( String net: nets ){

									if ( net == net_name ){

										return( true );
									}
								}
							}
						}

						return( false );
					}
					case FT_IS_PRIVATE:{

						TOTorrent t = dm.getTorrent();

						return( t != null && t.getPrivate());
					}
					case FT_IS_FORCE_START:{

						return( dm.isForceStart());
					}
					case FT_IS_CHECKING:{

						int state = dm.getState();

						if ( state == DownloadManager.STATE_CHECKING ){

							return( true );

						}else if ( state == DownloadManager.STATE_SEEDING ){

							DiskManager disk_manager = dm.getDiskManager();

							if ( disk_manager != null ){

								return( disk_manager.getCompleteRecheckStatus() != -1 );
							}
						}

						return( false );
					}
					case FT_IS_COMPLETE:{

						return( dm.isDownloadComplete( false ));
					}
					case FT_IS_STOPPED:{

						int state = dm.getState();

						return( state == DownloadManager.STATE_STOPPED && !dm.isPaused());
					}
					case FT_IS_ERROR:{

						int state = dm.getState();

						return( state == DownloadManager.STATE_ERROR );
					}
					case FT_IS_MAGNET:{

						return( dm.getDownloadState().getFlag(DownloadManagerState.FLAG_METADATA_DOWNLOAD ));
					}
					case FT_IS_LOW_NOISE:{

						return( dm.getDownloadState().getFlag(DownloadManagerState.FLAG_LOW_NOISE ));
					}
					case FT_IS_PAUSED:{

						return( dm.isPaused());
					}
					case FT_CAN_ARCHIVE:{

						Download dl = PluginCoreUtils.wrap( dm );

						return( dl != null && dl.canStubbify());
					}
					case FT_GE:
					case FT_GT:
					case FT_LE:
					case FT_LT:
					case FT_EQ:
					case FT_NEQ:{

						Number n1 = getNumeric( dm, tags, params, 0 );
						Number n2 = getNumeric( dm, tags, params, 1 );

						switch( fn_type ){

							case FT_GE:
								return( n1.doubleValue() >= n2.doubleValue());
							case FT_GT:
								return( n1.doubleValue() > n2.doubleValue());
							case FT_LE:
								return( n1.doubleValue() <= n2.doubleValue());
							case FT_LT:
								return( n1.doubleValue() < n2.doubleValue());
							case FT_EQ:
								return( n1.doubleValue() == n2.doubleValue());
							case FT_NEQ:
								return( n1.doubleValue() != n2.doubleValue());
						}

						return( false );
					}
					case FT_CONTAINS:{

						String[]	s1s = getStrings( dm, params, 0 );
						
						String		s2 = getString( dm, params, 1 );

						for ( String s1: s1s ){
						
							if ( s1.contains( s2 )){
								
								return( true );
							}
						}
						
						return( false );
					}
					case FT_MATCHES:{

						String[]	s1s = getStrings( dm, params, 0 );

						if ( params[1] == null ){

							return( false );

						}else{
							
							Pattern pattern;
							
							if ( params[1] instanceof Pattern ){
						
								pattern = (Pattern)params[1];
								
							}else{
	
								try{
									pattern = Pattern.compile((String)params[1], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE );
	
									params[1] = pattern;							

								}catch( Throwable e ){

									setError( "Invalid constraint pattern: " + params[1] + ": " + e.getMessage());
								
									params[1] = null;
									
									return( false );
								}
							}
							
							Object[] cache = matches_cache.get( dm );
							
							if ( cache != null ){
								
								if ( cache[0] == s1s && cache[1] == pattern ){
									
									return((Boolean)cache[2]);
								}
							}
														
							boolean result = false;
							
							for ( String s1: s1s ){
							
								if ( pattern.matcher( s1 ).find()){
									
									result = true;
									
									break;
								}
							}
							
							matches_cache.put( dm, new Object[]{ s1s, pattern, result });
							
							return( result );
						}
					}
					case FT_JAVASCRIPT:{

						Object result =
							handler.tag_manager.evalScript(
								tag,
								"javascript( " + (String)params[0] + ")",
								dm,
								"inTag" );

						if ( result instanceof Boolean ){

							return((Boolean)result);
							
						}else if ( result instanceof Throwable ){
							
							setError( Debug.getNestedExceptionMessage((Throwable)result ));
						}

						return( false );
					}
					case FT_HOURS_TO_SECS:{

						Number n1 = getNumeric( dm, tags, params, 0 );
						
						return((long)( n1.doubleValue() * 60*60 ));
					}
					case FT_DAYS_TO_SECS:{

						Number n1 = getNumeric( dm, tags, params, 0 );
						
						return((long)( n1.doubleValue() * 24*60*60 ));
					}
					case FT_WEEKS_TO_SECS:{

						Number n1 = getNumeric( dm, tags, params, 0 );
						
						return((long)( n1.doubleValue() * 7*24*60*60 ));
					}
					case FT_GET_CONFIG:{
						
						String key = (String)params[0];
						
						long now = SystemTime.getMonotonousTime();
						
						Object[] existing = config_value_cache.get( key );
						
						if ( existing != null ){
							
							if ( now - ((Long)existing[0]) < 60*1000 ){
								
								return( existing[1]);
							}
						}
						
						String[] entry = config_key_map.get( key );
						
						if ( entry[0] == CONFIG_FLOAT ){
							
							Object result = COConfigurationManager.getFloatParameter(entry[1]);
							
							config_value_cache.put( key, new Object[]{ now, result });
							
							return( result );
						}
						
						setError( "Error getting config value for '" + key + "'" );
						
						return( 0 );
					}
				}

				return( false );
			}

			private boolean
			getStringLiteral(
				Object[]	args,
				int			index )
			{
				Object _arg = args[index];

				if ( _arg instanceof String ){

					String arg = (String)_arg;

					if ( GeneralUtils.startsWithDoubleQuote( arg ) && GeneralUtils.endsWithDoubleQuote( arg )){

						args[index] = arg.substring( 1, arg.length() - 1 );

						return( true );
					}
				}

				return( false );
			}

			private boolean
			getNumericLiteral(
				Object[]	args,
				int			index )
			{
				Object arg = args[index];

				if ( arg instanceof Number ){

					return( true );
					
				}else if ( arg instanceof String ){
					
					try{
						Double d = Double.parseDouble( (String)arg );
						
						args[0] = d;
						
						return( true );
								
					}catch( Throwable e ){
						
					}
				}

				return( false );
			}
			
			private String
			getString(
				DownloadManager		dm,
				Object[]			args,
				int					index )
			{
				String str = (String)args[index];

				if ( GeneralUtils.startsWithDoubleQuote( str ) && GeneralUtils.endsWithDoubleQuote( str )){

					return( str.substring( 1, str.length() - 1 ));

				}else if ( str.equals( "name" )){

					return( dm.getDisplayName());

				}else{

					setError( "Invalid constraint string: " + str );

					String result = "\"\"";

					args[index] = result;

					return( result );
				}
			}

			private String[]
			getStrings(
				DownloadManager		dm,
				Object[]			args,
				int					index )
			{
				String str = (String)args[index];

				if ( GeneralUtils.startsWithDoubleQuote( str ) && GeneralUtils.endsWithDoubleQuote( str )){

					return( new String[]{ str.substring( 1, str.length() - 1 )});

				}else if ( str.equals( "name" )){

					return( new String[]{ dm.getDisplayName()});

				}else if ( str.equals( "file_names" ) || str.equals( "filenames" )){
					
					String[] result = (String[])dm.getUserData( DM_FILE_FILE_NAMES );
					
					if ( result == null ){
						
						DiskManagerFileInfo[] files = dm.getDiskManagerFileInfoSet().getFiles();
						
						result = new String[files.length];
						
						for ( int i=0;i<files.length;i++){
							
							result[i] = files[i].getFile( false ).getName();
						}
						
						dm.setUserData( DM_FILE_FILE_NAMES, result );
					}
					
					return( result );
					
				}else{

					setError( "Invalid constraint string: " + str );

					String result = "\"\"";

					args[index] = result;

					return( new String[]{ result });
				}
			}
			
			private Number
			getNumeric(
				DownloadManager		dm,
				List<Tag>			tags,
				Object[]			args,
				int					index )
			{
				Object arg = args[index];

				if ( arg instanceof Number ){

					return((Number)arg);
					
				}else if ( arg instanceof ConstraintExpr ){
					
					return((Number)((ConstraintExpr)arg).eval(dm, tags));
				}
				
				String str = (String)arg;

				Number result = 0;

				try{
					if ( str.equals( Constants.INFINITY_STRING )){
						
						result = Integer.MAX_VALUE;
						
						return( result );
						
					}else if ( Character.isDigit( str.charAt(0))){

						if ( str.contains( "." )){

							result = Float.parseFloat( str );

						}else{

							result = Long.parseLong( str );
						}

						return( result );
						
					}else{

						int[] kw_details = keyword_map.get( str.toLowerCase( Locale.US ));

						if ( kw_details == null ){

							setError(  "Invalid constraint keyword: " + str );

							return( result );
						}
						
						int kw = kw_details[0];

						result = null;	// don't cache any results below as they are variable
						
						switch( kw ){
							case KW_SHARE_RATIO:{

								int sr = dm.getStats().getShareRatio();

								if ( sr == -1 ){

									return( Integer.MAX_VALUE );

								}else{

									return( new Float( sr/1000.0f ));
								}
							}
							case KW_PERCENT:{

									// 0->1000

								int percent = dm.getStats().getPercentDoneExcludingDND();

								return( new Float( percent/10.0f ));
							}
							case KW_AGE:{

								long added = dm.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );

								if ( added <= 0 ){

									return( 0 );
								}

								return(( SystemTime.getCurrentTime() - added )/1000 );		// secs
							}
							case KW_COMPLETED_AGE:{

								long comp = dm.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_COMPLETED_TIME );

								if ( comp <= 0 ){

									return( 0 );
								}

								return(( SystemTime.getCurrentTime() - comp )/1000 );		// secs
							}
							case KW_PEER_MAX_COMP:{

								PEPeerManager pm = dm.getPeerManager();
								
								if ( pm == null ){
									
									return( 0 );
								}
								
								return(	new Float( pm.getMaxCompletionInThousandNotation( false )/10.0f ));
							}
							case KW_LEECHER_MAX_COMP:{

								PEPeerManager pm = dm.getPeerManager();
								
								if ( pm == null ){
									
									return( 0 );
								}
								
								return(	new Float( pm.getMaxCompletionInThousandNotation( true )/10.0f ));
							}

							case KW_PEER_AVERAGE_COMP:{

								PEPeerManager pm = dm.getPeerManager();
								
								if ( pm == null ){
									
									return( 0 );
								}
								
								return(	new Float( pm.getAverageCompletionInThousandNotation()/10.0f ));
							}
							case KW_DOWNLOADING_FOR:{

								return( dm.getStats().getSecondsDownloading());
							}
							case KW_SEEDING_FOR:{

								return( dm.getStats().getSecondsOnlySeeding());
							}
							case KW_LAST_ACTIVE:{

								DownloadManagerState dms = dm.getDownloadState();

								long	timestamp = dms.getLongAttribute( DownloadManagerState.AT_LAST_ADDED_TO_ACTIVE_TAG );

								if ( timestamp <= 0 ){

									return( Long.MAX_VALUE );
								}

								return(( SystemTime.getCurrentTime() - timestamp )/1000 );
							}
							case KW_RESUME_IN:{

								long resume_millis = dm.getAutoResumeTime();

								long	now = SystemTime.getCurrentTime();

								if ( resume_millis <= 0 || resume_millis <= now ){

									return( 0 );
								}

								return(( resume_millis - now )/1000 );
							}
							case KW_MIN_OF_HOUR:{

								long	now = SystemTime.getCurrentTime();

								GregorianCalendar cal = new GregorianCalendar();

								cal.setTime( new Date( now ));

								return( cal.get( Calendar.MINUTE ));
							}
							case KW_HOUR_OF_DAY:{

								long	now = SystemTime.getCurrentTime();

								GregorianCalendar cal = new GregorianCalendar();

								cal.setTime( new Date( now ));

								return( cal.get( Calendar.HOUR_OF_DAY ));
							}
							case KW_DAY_OF_WEEK:{

								long	now = SystemTime.getCurrentTime();

								GregorianCalendar cal = new GregorianCalendar();

								cal.setTime( new Date( now ));

								return( cal.get( Calendar.DAY_OF_WEEK ));
							}
							case KW_SWARM_MERGE:{

								return( dm.getDownloadState().getLongAttribute( DownloadManagerState.AT_MERGED_DATA ));
							}
							case KW_SEED_COUNT:{

								TRTrackerScraperResponse response = dm.getTrackerScrapeResponse();

								int	seeds = dm.getNbSeeds();

								if ( response != null && response.isValid()){

									seeds = Math.max( seeds, response.getSeeds());
								}

								return( Math.max( 0, seeds ));
							}
							case KW_PEER_COUNT:{

								TRTrackerScraperResponse response = dm.getTrackerScrapeResponse();

								int	peers = dm.getNbSeeds();

								if ( response != null && response.isValid()){

									peers = Math.max( peers, response.getPeers());
								}

								return( Math.max( 0, peers ));
							}
							case KW_SEED_PEER_RATIO:{

								TRTrackerScraperResponse response = dm.getTrackerScrapeResponse();

								int	seeds = dm.getNbSeeds();
								int	peers = dm.getNbPeers();

								if ( response != null && response.isValid()){

									seeds = Math.max( seeds, response.getSeeds());
									peers = Math.max( peers, response.getPeers());
								}

								float ratio;

								if ( peers < 0 || seeds < 0 ){

									ratio = 0;

								}else{

									if ( peers == 0 ){

										if ( seeds == 0 ){

											ratio = 0;

										}else{

											return( Integer.MAX_VALUE );
										}
									}else{

										ratio = (float)seeds/peers;
									}
								}

								return( ratio );
							}
							case KW_TAG_AGE:{

								long tag_added = tag.getTaggableAddedTime( dm );

								if ( tag_added <= 0 ){

									return( 0 );
								}

								long age = (( SystemTime.getCurrentTime() - tag_added )/1000 );		// secs

								if ( age < 0 ){

									age = 0;
								}

								return( age );
							}

							case KW_SIZE:{
								
								return( dm.getSize());
							}
							case KW_SIZE_MB:{
								
								return( dm.getSize()/(1024*1024L));
							}
							case KW_SIZE_GB:{
								
								return( dm.getSize()/(1024*1024*1024L));
							}
							case KW_FILE_COUNT:{
								
								return( dm.getNumFileInfos());
							}
							case KW_AVAILABILITY:{

								PEPeerManager pm = dm.getPeerManager();
								
								if ( pm == null ){
									
									return( -1f );
								}
								
								float avail = pm.getMinAvailability();
								
								return(	new Float( avail ));
							}
							case KW_UP_IDLE:{
								
								long secs = dm.getStats().getTimeSinceLastDataSentInSeconds();
								
								if ( secs < 0 ){
									
									return( Long.MAX_VALUE );
									
								}else{
									
									return( secs );
								}
							}
							case KW_DOWN_IDLE:{
								
								long secs = dm.getStats().getTimeSinceLastDataReceivedInSeconds();
								
								if ( secs < 0 ){
									
									return( Long.MAX_VALUE );
									
								}else{
									
									return( secs );
								}
							}
							case KW_DOWNLOADED:{
								
								return( dm.getStats().getTotalGoodDataBytesReceived());
							}
							case KW_UPLOADED:{
								
								return( dm.getStats().getTotalDataBytesSent());
							}
							default:{

								setError( "Invalid constraint keyword: " + str );

								return( result );
							}
						}
					}
				}catch( Throwable e){

					setError( "Invalid constraint numeric: " + str );

					return( result );

				}finally{

					if ( result != null ){

							// cache literal results

						args[index] = result;
					}
				}
			}

			@Override
			public String
			getString()
			{
				return( func_name + "(" + params_expr.getString() + ")" );
			}
		}
	}

	public static void
	main(
		String[]	args )
	{
		TagPropertyConstraintHandler handler = new TagPropertyConstraintHandler();

		//System.out.println( handler.compileConstraint( "!(hasTag(\"bil\") && (hasTag( \"fred\" ))) || hasTag(\"toot\")" ).getString());
		System.out.println( handler.compileConstraint( "hasTag(  �Seeding Only� ) && seeding_for > h2s(10) || hasTag(\"sdsd\") " ).getString());
	}
}
